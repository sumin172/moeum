package com.moeum.platform.llm.provider

import com.moeum.platform.llm.*
import com.moeum.platform.llm.provider.cloudflare.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.http.HttpStatus
import com.moeum.platform.job.JobFailure
import com.moeum.platform.job.JobFailureKind
import org.springframework.web.client.RestClient

class CloudflareProviderTest {
    private val builder = RestClient.builder().baseUrl("https://cloudflare.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = CloudflareProvider(builder.build(), CloudflareProperties("account", "test-token"))
    private val route = LlmRoute("cloudflare", "@cf/qwen/qwen3-30b-a3b-fp8", 150, 0)
    private val request = LlmRequest("conversation-response", "시스템", listOf(
        LlmMessage(LlmRole.USER, "안녕"), LlmMessage(LlmRole.ASSISTANT, "반가워요"),
        LlmMessage(LlmRole.USER, "피곤해"),
    ), "v2")

    @Test
    fun `인증과 역할 출력 상한을 전송하고 마지막 사용자 입력에 추론 억제를 요청한다`() {
        server.expect(requestTo("https://cloudflare.test/client/v4/accounts/account/ai/v1/chat/completions"))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andExpect(jsonPath("$.model").value(route.model))
            .andExpect(jsonPath("$.max_tokens").value(150))
            .andExpect(jsonPath("$.stream").value(false))
            .andExpect(jsonPath("$.messages[0].role").value("system"))
            .andExpect(jsonPath("$.messages[1].content").value("안녕"))
            .andExpect(jsonPath("$.messages[2].role").value("assistant"))
            .andExpect(jsonPath("$.messages[3].content").value("피곤해\n/no_think"))
            .andRespond(withSuccess("""{"model":"actual","choices":[{"message":{"content":"<think>추론</think> 쉬어가세요.","reasoning_content":"숨긴 추론"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":25,"prompt_tokens_details":{"cached_tokens":10}}} """, MediaType.APPLICATION_JSON))
        val result = provider.generate(route, request)
        assertThat(result.text).isEqualTo("쉬어가세요.")
        assertThat(result.model).isEqualTo("actual")
        assertThat(result.inputTokens).isEqualTo(100)
        assertThat(result.outputTokens).isEqualTo(25)
        assertThat(result.cachedInputTokens).isEqualTo(10)
        assertThat(result.errorCode).isNull()
        server.verify()
    }

    @ParameterizedTest
    @CsvSource(value = ["stop,NULL", "length,OUTPUT_TRUNCATED", "content_filter,OUTPUT_BLOCKED", "tool_calls,UNEXPECTED_FINISH_REASON", "NULL,UNEXPECTED_FINISH_REASON"], nullValues = ["NULL"])
    fun `정상 종료만 성공으로 인정하고 누락 사용량은 0으로 처리한다`(reason: String?, error: String?) {
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        server.expect(anything()).andRespond(withSuccess("""{"choices":[{"message":{"content":"응답"},"finish_reason":$reasonJson}]}""", MediaType.APPLICATION_JSON))
        val result = provider.generate(route, request)
        assertThat(result.errorCode).isEqualTo(error)
        assertThat(result.inputTokens).isZero()
        assertThat(result.outputTokens).isZero()
        assertThat(result.model).isEqualTo(route.model)
        server.verify()
    }

    @ParameterizedTest
    @CsvSource(value = ["<think>미완결,UNEXPECTED_FINISH_REASON", "<think></think>,EMPTY_CONTENT"])
    fun `추론만 있거나 미완결인 본문은 저장하지 않는다`(content: String, error: String) {
        server.expect(anything()).andRespond(withSuccess("""{"choices":[{"message":{"content":"$content"},"finish_reason":"stop"}]}""", MediaType.APPLICATION_JSON))
        assertThat(provider.generate(route, request).errorCode).isEqualTo(error)
        server.verify()
    }

    @Test
    fun `라우트로 쓸 때 필요한 계정 ID와 API Token 중 비어 있는 설정 이름만 알린다`() {
        assertThat(provider.missingSettings()).isEmpty()
        assertThat(CloudflareProvider(builder.build(), CloudflareProperties()).missingSettings())
            .containsExactly("moeum.cloudflare.account-id", "moeum.cloudflare.api-token")
        assertThat(CloudflareProvider(builder.build(), CloudflareProperties(accountId = "account")).missingSettings())
            .containsExactly("moeum.cloudflare.api-token")
    }

    @Test
    fun `키 누락은 네트워크 호출 없이 인증 실패로 판정한다`() {
        assertThatThrownBy { CloudflareProvider(builder.build(), CloudflareProperties()).generate(route, request) }
            .isInstanceOf(LlmException::class.java)
            .hasMessage("Cloudflare 인증 설정이 없습니다")
        server.verify()
    }

    @ParameterizedTest
    @CsvSource("401,PERMANENT", "429,RATE_LIMITED", "503,TRANSIENT")
    fun `공급자 HTTP 오류는 원문 없이 공통 재시도 정책으로 전달한다`(status: Int, kind: JobFailureKind) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status))
            .header("Retry-After", "120")
            .body("private provider error body"))
        val client = RoutingLlmClient(listOf(provider), LlmProperties(routes = mapOf(request.purpose to route))) { }
        val error = org.junit.jupiter.api.assertThrows<LlmException> { client.generate(request) }
        assertThat(error.message).isEqualTo("LLM 호출 실패")
        assertThat(error.cause).isNull()
        assertThat(error.errorCode).isEqualTo("HTTP_$status")
        assertThat(JobFailure.classify(error).kind).isEqualTo(kind)
        if (status == 429) assertThat(error.retryAfter).isEqualTo(java.time.Duration.ofSeconds(120))
        server.verify()
    }
}
