package com.moeum.platform.llm.provider

import com.moeum.platform.llm.*
import com.moeum.platform.llm.provider.gemini.GeminiProperties
import com.moeum.platform.llm.provider.gemini.GeminiProvider
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

// 실제 API 대신 요청 JSON 형태와 응답 해석을 고정한다(실제 호출 검증은 스모크 테스트에서).
class GeminiProviderTest {

    private val builder = RestClient.builder().baseUrl("https://gemini.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = GeminiProvider(builder.build(), GeminiProperties(apiKey = "test-key"))

    private val request = LlmRequest(
        purpose = "chat",
        systemPrompt = "시스템 지시",
        messages = listOf(LlmMessage(LlmRole.USER, "안녕"), LlmMessage(LlmRole.ASSISTANT, "반가워요"), LlmMessage(LlmRole.USER, "오늘 피곤해")),
        promptVersion = "v1",
    )

    @Test
    fun `API 키가 없으면 필수 설정 이름만 알리고 기동용 바인딩은 막지 않는다`() {
        assertThat(provider.missingSettings()).isEmpty()
        assertThat(GeminiProvider(builder.build(), GeminiProperties()).missingSettings()).containsExactly("moeum.gemini.api-key")
    }

    @Test
    fun `토큰 상한과 thinking 예산을 generationConfig로 보내고, 역할을 Gemini 형식으로 바꾼다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-flash-latest:generateContent"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-goog-api-key", "test-key"))
            .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("시스템 지시"))
            .andExpect(jsonPath("$.systemInstruction.role").doesNotExist())
            .andExpect(jsonPath("$.contents[0].role").value("user"))
            .andExpect(jsonPath("$.contents[1].role").value("model"))
            .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(150))
            .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingBudget").value(0))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").doesNotExist())
            .andRespond(
                withSuccess(
                    """
                    {"candidates":[{"content":{"role":"model","parts":[{"text":"속으로 생각","thought":true},{"text":"푹 쉬세요."}]},"finishReason":"STOP"}],
                     "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":8,"cachedContentTokenCount":30,"thoughtsTokenCount":2},
                     "modelVersion":"gemini-2.5-flash-001"}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = provider.generate(LlmRoute("gemini", "gemini-flash-latest", maxOutputTokens = 150, thinkingBudget = 0), request)

        server.verify()
        assertThat(result.text).isEqualTo("푹 쉬세요.")
        assertThat(result.model).isEqualTo("gemini-2.5-flash-001")
        assertThat(result.inputTokens).isEqualTo(40)
        assertThat(result.outputTokens).isEqualTo(10) // 응답 8 + thinking 2
        assertThat(result.cachedInputTokens).isEqualTo(30)
        assertThat(result.finishReason).isEqualTo("STOP")
        assertThat(result.errorCode).isNull()
    }

    @Test
    fun `JSON 응답 형식이면 responseMimeType을 지정하고, thinking 예산이 없으면 thinkingConfig를 보내지 않는다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
            .andExpect(jsonPath("$.generationConfig.thinkingConfig").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))))
            .andRespond(withSuccess("""{"candidates":[{"content":{"parts":[{"text":"{}"}]},"finishReason":"STOP"}]}""", MediaType.APPLICATION_JSON))

        provider.generate(LlmRoute("gemini", "flash", 2000), request.copy(responseFormat = LlmResponseFormat.JSON))

        server.verify()
    }

    @Test
    fun `내용 없이 토큰 상한에 도달해도 빈 응답 재시도로 분류하지 않는다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andRespond(withSuccess("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""", MediaType.APPLICATION_JSON))

        val result = provider.generate(LlmRoute("gemini", "flash", 10), request)
        assertThat(result.errorCode).isEqualTo("OUTPUT_TRUNCATED")
    }

    @ParameterizedTest
    @CsvSource(value = ["STOP,NULL", "MAX_TOKENS,OUTPUT_TRUNCATED", "SAFETY,OUTPUT_BLOCKED", "RECITATION,OUTPUT_BLOCKED", "NEW_REASON,UNEXPECTED_FINISH_REASON", "NULL,UNEXPECTED_FINISH_REASON"], nullValues = ["NULL"])
    fun `본문이 있어도 종료 사유로 결과 유효성을 판정한다`(reason: String?, expected: String?) {
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andRespond(withSuccess("""{"candidates":[{"content":{"parts":[{"text":"일부 본문"}]},"finishReason":$reasonJson}]}""", MediaType.APPLICATION_JSON))
        assertThat(provider.generate(LlmRoute("gemini", "flash", 10), request).errorCode).isEqualTo(expected)
        server.verify()
    }

    @Test
    fun `후보 없는 입력 차단도 빈 응답과 구분한다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andRespond(withSuccess("""{"promptFeedback":{"blockReason":"SAFETY"},"modelVersion":"flash-001","usageMetadata":{"promptTokenCount":20}}""", MediaType.APPLICATION_JSON))
        val result = provider.generate(LlmRoute("gemini", "flash", 10), request)
        assertThat(result.errorCode).isEqualTo("OUTPUT_BLOCKED")
        assertThat(result.finishReason).isEqualTo("SAFETY")
        assertThat(result.model).isEqualTo("flash-001")
        assertThat(result.inputTokens).isEqualTo(20)
    }

    @Test
    fun `정상 종료라도 본문이 비면 빈 응답으로 판정한다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andRespond(withSuccess("""{"candidates":[{"finishReason":"STOP"}]}""", MediaType.APPLICATION_JSON))
        assertThat(provider.generate(LlmRoute("gemini", "flash", 10), request).errorCode).isEqualTo("EMPTY_CONTENT")
    }

    @ParameterizedTest
    @CsvSource("MAX_TOKENS,OUTPUT_TRUNCATED", "SAFETY,OUTPUT_BLOCKED")
    fun `출력 잘림과 차단은 호출 뒤 라우터에서 거절하고 공급자 서킷은 실패로 집계하지 않는다`(reason: String, expected: String) {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andRespond(withSuccess("""{"candidates":[{"content":{"parts":[{"text":"중간 본문"}]},"finishReason":"$reason"}]}""", MediaType.APPLICATION_JSON))
        val breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom().recordExceptions(LlmException::class.java).build())
        val delegate = provider
        val wrapped = object : LlmProvider by delegate {
            override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult =
                breaker.executeSupplier { delegate.generate(route, request) }
        }
        val client = RoutingLlmClient(listOf(wrapped), LlmProperties(routes = mapOf("chat" to LlmRoute("gemini", "flash", 10)))) { }
        assertThatThrownBy { client.generate(request) }.isInstanceOfSatisfying(LlmException::class.java) {
            assertThat(it.errorCode).isEqualTo(expected)
        }
        assertThat(breaker.metrics.numberOfFailedCalls).isZero()
        assertThat(breaker.metrics.numberOfSuccessfulCalls).isEqualTo(1)
    }
}
