package com.moeum.platform.llm.provider

import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmResponseFormat
import com.moeum.platform.llm.LlmRole
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.gemini.GeminiProperties
import com.moeum.platform.llm.provider.gemini.GeminiProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
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
    }

    @Test
    fun `JSON 응답 형식이면 responseMimeType을 지정하고, thinking 예산이 없으면 thinkingConfig를 보내지 않는다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
            .andExpect(jsonPath("$.generationConfig.thinkingConfig").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("null"))))
            .andRespond(withSuccess("""{"candidates":[{"content":{"parts":[{"text":"{}"}]}}]}""", MediaType.APPLICATION_JSON))

        provider.generate(LlmRoute("gemini", "flash", 2000), request.copy(responseFormat = LlmResponseFormat.JSON))

        server.verify()
    }

    @Test
    fun `토큰 상한 등으로 내용 없이 끝나면 finishReason과 함께 LlmException을 던진다`() {
        server.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent"))
            .andRespond(withSuccess("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { provider.generate(LlmRoute("gemini", "flash", 10), request) }
            .isInstanceOf(LlmException::class.java)
            .hasMessageContaining("MAX_TOKENS")
    }
}
