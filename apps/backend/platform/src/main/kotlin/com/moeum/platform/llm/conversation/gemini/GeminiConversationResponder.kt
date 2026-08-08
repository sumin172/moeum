package com.moeum.platform.llm.conversation.gemini

import com.moeum.kernel.UuidV7
import com.moeum.platform.llm.conversation.ConversationRequest
import com.moeum.platform.llm.conversation.ConversationResponder
import com.moeum.platform.llm.conversation.ConversationResponse
import com.moeum.platform.llm.conversation.ConversationResponseException
import com.moeum.platform.llm.conversation.LlmMessage
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

private const val PROVIDER = "google"
private const val PROMPT_VERSION = "v1"

// 저널링 앱의 대화 상대로서 소재를 유도하는 시스템 지시. 프롬프트 기반이라 100% 강제는 아니고 최소 가드레일 목적.
private const val SYSTEM_INSTRUCTION =
    "당신은 사용자의 일상 기록을 돕는 다정한 대화 상대입니다. 사용자가 겪은 하루에 공감하며 자연스럽게 응답하세요. " +
        "코드 작성, 시사·정치 논쟁, 전문 의료·법률 상담처럼 일상 대화와 무관한 요청이 오면 답변이 어려움을 정중히 안내하고 일상 주제로 되돌리세요."

@Component
class GeminiConversationResponder(
    private val geminiRestClient: RestClient,
    private val properties: GeminiProperties,
) : ConversationResponder {

    private val log = LoggerFactory.getLogger(GeminiConversationResponder::class.java)

    // recordExceptions(application.yml)이 실제 예외 타입을 보고 서킷 개폐를 판단하므로,
    // 이 메서드 안에서 예외를 감싸지 않고 그대로 전파시킨다 — 정규화는 fallback에서만 한다.
    @CircuitBreaker(name = "geminiConversationClient", fallbackMethod = "fallback")
    override fun respond(request: ConversationRequest): ConversationResponse {
        val requestBody = GeminiGenerateContentRequest(
            systemInstruction = GeminiSystemInstruction(parts = listOf(GeminiPart(request.systemPrompt ?: SYSTEM_INSTRUCTION))),
            contents = request.messages.map { it.toGeminiContent() },
        )

        val response = geminiRestClient.post()
            .uri("/v1beta/models/{model}:generateContent", properties.model)
            .header("x-goog-api-key", properties.apiKey)
            .body(requestBody)
            .retrieve()
            .body<GeminiGenerateContentResponse>()

        val text = response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw ConversationResponseException("Gemini 응답에 유효한 content가 없습니다(안전 필터 등으로 차단됐을 수 있음)")

        return ConversationResponse(
            generationId = UuidV7.generate(),
            content = text,
            model = response.modelVersion ?: properties.model,
            provider = PROVIDER,
            promptVersion = PROMPT_VERSION,
            inputTokens = response.usageMetadata?.promptTokenCount ?: 0,
            outputTokens = response.usageMetadata?.candidatesTokenCount ?: 0,
        )
    }

    @Suppress("unused")
    fun fallback(request: ConversationRequest, e: Throwable): ConversationResponse {
        log.error("Gemini 호출 실패: model={}, error={}", properties.model, e.message, e)
        throw ConversationResponseException("Gemini 응답 생성 실패", e)
    }
}

private fun LlmMessage.toGeminiContent(): GeminiContent =
    GeminiContent(role = if (role == "assistant") "model" else "user", parts = listOf(GeminiPart(content)))

data class GeminiGenerateContentRequest(
    val systemInstruction: GeminiSystemInstruction,
    val contents: List<GeminiContent>,
)

data class GeminiSystemInstruction(val parts: List<GeminiPart>)
data class GeminiContent(val role: String, val parts: List<GeminiPart>)
data class GeminiPart(val text: String)

data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsageMetadata? = null,
    val modelVersion: String? = null,
)

data class GeminiCandidate(val content: GeminiContent? = null)
data class GeminiUsageMetadata(val promptTokenCount: Int = 0, val candidatesTokenCount: Int = 0)
