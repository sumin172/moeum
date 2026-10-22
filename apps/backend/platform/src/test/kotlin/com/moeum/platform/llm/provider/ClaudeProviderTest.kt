package com.moeum.platform.llm.provider

import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRole
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.claude.ClaudeProperties
import com.moeum.platform.llm.provider.claude.ClaudeProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

// 결제 설정 전이라 실제 호출은 검증하지 않았다 — 요청 JSON 형태와 응답 해석만 고정한다.
class ClaudeProviderTest {

    private val builder = RestClient.builder().baseUrl("https://claude.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = ClaudeProvider(builder.build(), ClaudeProperties(apiKey = "test-key"))

    @Test
    fun `Messages API 형식으로 보내고 텍스트 블록과 사용량을 해석한다`() {
        server.expect(requestTo("https://claude.test/v1/messages"))
            .andExpect(header("x-api-key", "test-key"))
            .andExpect(header("anthropic-version", "2023-06-01"))
            .andExpect(jsonPath("$.model").value("claude-haiku-4-5-20251001"))
            .andExpect(jsonPath("$.max_tokens").value(2000))
            .andExpect(jsonPath("$.system").value("시스템 지시"))
            .andExpect(jsonPath("$.messages[0].role").value("user"))
            .andExpect(jsonPath("$.messages[1].role").value("assistant"))
            .andExpect(jsonPath("$.thinking").doesNotExist())
            .andRespond(
                withSuccess(
                    """
                    {"content":[{"type":"text","text":"{\"title\":\"산책\",\"body\":\"걸었다\"}"}],
                     "model":"claude-haiku-4-5-20251001","stop_reason":"end_turn",
                     "usage":{"input_tokens":120,"output_tokens":40,"cache_read_input_tokens":0}}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = provider.generate(
            LlmRoute("claude", "claude-haiku-4-5-20251001", maxOutputTokens = 2000),
            LlmRequest(
                purpose = "diary",
                systemPrompt = "시스템 지시",
                messages = listOf(LlmMessage(LlmRole.USER, "원본"), LlmMessage(LlmRole.ASSISTANT, "이전 응답")),
                promptVersion = "v1",
            ),
        )

        server.verify()
        assertThat(result.text).isEqualTo("{\"title\":\"산책\",\"body\":\"걸었다\"}")
        assertThat(result.inputTokens).isEqualTo(120)
        assertThat(result.outputTokens).isEqualTo(40)
        assertThat(result.finishReason).isEqualTo("end_turn")
        assertThat(result.errorCode).isNull()
    }

    @ParameterizedTest
    @CsvSource(value = ["end_turn,NULL", "max_tokens,OUTPUT_TRUNCATED", "model_context_window_exceeded,OUTPUT_TRUNCATED", "refusal,OUTPUT_BLOCKED", "stop_sequence,UNEXPECTED_FINISH_REASON", "pause_turn,UNEXPECTED_FINISH_REASON", "NULL,UNEXPECTED_FINISH_REASON"], nullValues = ["NULL"])
    fun `본문이 있어도 정상 종료가 아니면 오류로 판정한다`(reason: String?, expected: String?) {
        val reasonJson = reason?.let { "\"$it\"" } ?: "null"
        server.expect(requestTo("https://claude.test/v1/messages"))
            .andRespond(withSuccess("""{"content":[{"type":"text","text":"일부 본문"}],"stop_reason":$reasonJson}""", MediaType.APPLICATION_JSON))
        val result = provider.generate(LlmRoute("claude", "haiku", 20),
            LlmRequest("chat", "system", listOf(LlmMessage(LlmRole.USER, "안녕")), "v1"))
        assertThat(result.errorCode).isEqualTo(expected)
        server.verify()
    }

    @ParameterizedTest
    @CsvSource("end_turn,EMPTY_CONTENT", "max_tokens,OUTPUT_TRUNCATED", "refusal,OUTPUT_BLOCKED")
    fun `빈 본문도 종료 사유를 우선하고 생략된 사용량은 0으로 해석한다`(reason: String, expected: String) {
        server.expect(requestTo("https://claude.test/v1/messages"))
            .andRespond(withSuccess("""{"content":[],"stop_reason":"$reason","usage":{"input_tokens":12}}""", MediaType.APPLICATION_JSON))
        val result = provider.generate(LlmRoute("claude", "haiku", 20),
            LlmRequest("chat", "system", listOf(LlmMessage(LlmRole.USER, "안녕")), "v1"))
        assertThat(result.errorCode).isEqualTo(expected)
        assertThat(result.inputTokens).isEqualTo(12)
        assertThat(result.outputTokens).isZero()
        assertThat(result.cachedInputTokens).isZero()
    }
}
