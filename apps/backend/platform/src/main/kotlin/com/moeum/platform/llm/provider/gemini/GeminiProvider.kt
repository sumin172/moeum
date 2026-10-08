package com.moeum.platform.llm.provider.gemini

import com.fasterxml.jackson.annotation.JsonInclude
import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmResponseFormat
import com.moeum.platform.llm.LlmRole
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Component
class GeminiProvider(
    private val geminiRestClient: RestClient,
    private val properties: GeminiProperties,
) : LlmProvider {

    private val log = LoggerFactory.getLogger(GeminiProvider::class.java)

    override val name = "gemini"
    override val vendor = "google"

    // 서킷은 provider 단위다(용도와 무관하게 Gemini 자체가 불안정하면 함께 빠르게 실패). 예외는 감싸지 않고 그대로
    // 전파해야 recordExceptions(application.yml)가 실제 예외 타입으로 개폐를 판단한다.
    @CircuitBreaker(name = "llm-gemini")
    override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
        val body = GeminiGenerateContentRequest(
            systemInstruction = GeminiContent(role = null, parts = listOf(GeminiPart(request.systemPrompt))),
            contents = request.messages.map { it.toGeminiContent() },
            generationConfig = GeminiGenerationConfig(
                maxOutputTokens = route.maxOutputTokens,
                responseMimeType = if (request.responseFormat == LlmResponseFormat.JSON) "application/json" else null,
                thinkingConfig = route.thinkingBudget?.let { GeminiThinkingConfig(thinkingBudget = it) },
            ),
        )

        val response = geminiRestClient.post()
            .uri("/v1beta/models/{model}:generateContent", route.model)
            .header("x-goog-api-key", properties.apiKey)
            .body(body)
            .retrieve()
            .body<GeminiGenerateContentResponse>()
            ?: throw LlmException("Gemini 응답 본문이 비어 있습니다")

        val candidate = response.candidates.firstOrNull()
        val text = candidate?.content?.parts.orEmpty().filterNot { it.thought == true }.mapNotNull { it.text }.joinToString("")
        if (text.isBlank()) {
            throw LlmException("Gemini 응답에 유효한 content가 없습니다(안전 필터·토큰 상한 등): finishReason=${candidate?.finishReason}")
        }

        val usage = response.usageMetadata
        // Gemini 2.5+는 같은 prefix로 시작하는 요청에 암묵적 캐싱을 자동 적용한다 — 실제 히트 여부 관측용 로그.
        if ((usage?.cachedContentTokenCount ?: 0) > 0) {
            log.debug("Gemini 캐시 히트: cachedTokens={}, promptTokens={}", usage?.cachedContentTokenCount, usage?.promptTokenCount)
        }
        return LlmProviderResult(
            text = text,
            model = response.modelVersion ?: route.model,
            inputTokens = usage?.promptTokenCount ?: 0,
            // thinking 토큰도 출력으로 과금되므로 함께 센다
            outputTokens = (usage?.candidatesTokenCount ?: 0) + (usage?.thoughtsTokenCount ?: 0),
            cachedInputTokens = usage?.cachedContentTokenCount ?: 0,
            finishReason = candidate?.finishReason,
        )
    }
}

private fun LlmMessage.toGeminiContent(): GeminiContent =
    GeminiContent(role = if (role == LlmRole.ASSISTANT) "model" else "user", parts = listOf(GeminiPart(content)))

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GeminiGenerateContentRequest(
    val systemInstruction: GeminiContent,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GeminiGenerationConfig(
    val maxOutputTokens: Int,
    val responseMimeType: String? = null,
    val thinkingConfig: GeminiThinkingConfig? = null,
)

data class GeminiThinkingConfig(val thinkingBudget: Int)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GeminiContent(val role: String? = null, val parts: List<GeminiPart> = emptyList())

@JsonInclude(JsonInclude.Include.NON_NULL)
data class GeminiPart(val text: String? = null, val thought: Boolean? = null)

data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsageMetadata? = null,
    val modelVersion: String? = null,
)

data class GeminiCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

data class GeminiUsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val cachedContentTokenCount: Int = 0,
    val thoughtsTokenCount: Int = 0,
)
