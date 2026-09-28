package com.moeum.platform.llm.moment.gemini

import com.moeum.kernel.UuidV7
import com.moeum.platform.llm.conversation.gemini.GeminiProperties
import com.moeum.platform.llm.moment.ExtractedMoment
import com.moeum.platform.llm.moment.MomentExtractionException
import com.moeum.platform.llm.moment.MomentExtractionRequest
import com.moeum.platform.llm.moment.MomentExtractionResponse
import com.moeum.platform.llm.moment.MomentExtractor
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

private const val PROVIDER = "google"
private const val PROMPT_VERSION = "v1"

// 배치 추출용 지시. 자유 서술이 아니라 JSON 배열만 반환하도록 강제한다 (generationConfig.responseMimeType과 병행).
private const val SYSTEM_INSTRUCTION =
    "당신은 하루치 대화 로그에서 개별 '순간'(Moment)을 추출하는 시스템입니다. " +
        "대화에 실제로 등장한 사건·활동 단위로만 추출하고, 없는 내용을 지어내지 마세요. " +
        "오직 JSON 배열만 응답하세요. 각 원소는 " +
        "{\"type\": string, \"summary\": string, \"emotion\": string|null, \"confidence\": number|null, \"occurredAt\": string(ISO-8601)|null} " +
        "형태입니다. type은 MEAL, WORK, EXERCISE, SOCIAL, REST, EMOTION, OTHER 중 가장 가까운 것을 쓰되, " +
        "애매하면 OTHER를 쓰세요. summary는 한국어 한 문장으로 요약하세요."

@Component
class GeminiMomentExtractor(
    private val geminiRestClient: RestClient,
    private val properties: GeminiProperties,
    private val jsonMapper: JsonMapper,
) : MomentExtractor {

    private val log = LoggerFactory.getLogger(GeminiMomentExtractor::class.java)

    // recordExceptions(application.yml)이 실제 예외 타입을 보고 서킷 개폐를 판단하므로,
    // 이 메서드 안에서 예외를 감싸지 않고 그대로 전파시킨다 — 정규화는 fallback에서만 한다.
    @CircuitBreaker(name = "geminiMomentExtractionClient", fallbackMethod = "fallback")
    override fun extract(request: MomentExtractionRequest): MomentExtractionResponse {
        val requestBody = GeminiGenerateContentRequest(
            systemInstruction = GeminiSystemInstruction(parts = listOf(GeminiPart(SYSTEM_INSTRUCTION))),
            contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(request.rawTranscript)))),
            generationConfig = GeminiGenerationConfig(responseMimeType = "application/json"),
        )

        val response = geminiRestClient.post()
            .uri("/v1beta/models/{model}:generateContent", properties.model)
            .header("x-goog-api-key", properties.apiKey)
            .body(requestBody)
            .retrieve()
            .body<GeminiGenerateContentResponse>()

        val text = response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw MomentExtractionException("Gemini 응답에 유효한 content가 없습니다(안전 필터 등으로 차단됐을 수 있음)")

        val moments = runCatching {
            jsonMapper.readValue(text, Array<GeminiMomentPayload>::class.java).map { it.toExtractedMoment() }
        }.getOrElse { e -> throw MomentExtractionException("Gemini 응답 JSON 파싱 실패: $text", e) }

        return MomentExtractionResponse(
            generationId = UuidV7.generate(),
            moments = moments,
            model = response.modelVersion ?: properties.model,
            provider = PROVIDER,
            promptVersion = PROMPT_VERSION,
            inputTokens = response.usageMetadata?.promptTokenCount ?: 0,
            outputTokens = response.usageMetadata?.candidatesTokenCount ?: 0,
        )
    }

    @Suppress("unused")
    fun fallback(request: MomentExtractionRequest, e: Throwable): MomentExtractionResponse {
        log.error("Gemini Moment 추출 실패: model={}, error={}", properties.model, e.message, e)
        throw MomentExtractionException("Gemini Moment 추출 실패", e)
    }
}

private fun GeminiMomentPayload.toExtractedMoment(): ExtractedMoment =
    ExtractedMoment(type = type, summary = summary, emotion = emotion, confidence = confidence, occurredAt = occurredAt)

data class GeminiGenerateContentRequest(
    val systemInstruction: GeminiSystemInstruction,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig,
)

data class GeminiGenerationConfig(val responseMimeType: String)
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

private data class GeminiMomentPayload(
    val type: String,
    val summary: String,
    val emotion: String? = null,
    val confidence: Float? = null,
    val occurredAt: Instant? = null,
)
