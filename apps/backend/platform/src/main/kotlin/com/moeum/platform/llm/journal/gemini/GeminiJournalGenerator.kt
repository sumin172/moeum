package com.moeum.platform.llm.journal.gemini

import com.moeum.kernel.UuidV7
import com.moeum.platform.llm.conversation.gemini.GeminiProperties
import com.moeum.platform.llm.journal.JournalGenerationException
import com.moeum.platform.llm.journal.JournalGenerationRequest
import com.moeum.platform.llm.journal.JournalGenerationResponse
import com.moeum.platform.llm.journal.JournalGenerator
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import tools.jackson.databind.json.JsonMapper

private const val PROVIDER = "google"
private const val PROMPT_VERSION = "v1"

// Claude와 동일한 계약(JSON 객체만 응답)을 Gemini의 responseMimeType 강제로 구현한다.
private const val SYSTEM_INSTRUCTION =
    "당신은 사용자의 하루 대화 원본을 바탕으로 1인칭 일기를 쓰는 작가입니다. " +
        "대화에 실제로 등장한 내용만 사용하고, 없는 내용을 지어내지 마세요. " +
        "오직 JSON 객체만 응답하세요. {\"title\": string, \"body\": string} 형태입니다. " +
        "title은 그날을 대표하는 짧은 한국어 제목, body는 자연스러운 한국어 1인칭 일기 서술입니다."

// moeum.journal.provider=claude로 바꾸면 ClaudeJournalGenerator가 대신 활성화된다(둘 다 같은 JournalGenerator
// 계약을 구현하므로 호출부는 어느 쪽이 떠도 변경 없음).
@Component
@ConditionalOnProperty(name = ["moeum.journal.provider"], havingValue = "gemini", matchIfMissing = true)
class GeminiJournalGenerator(
    private val geminiRestClient: RestClient,
    private val properties: GeminiProperties,
    private val jsonMapper: JsonMapper,
) : JournalGenerator {

    private val log = LoggerFactory.getLogger(GeminiJournalGenerator::class.java)

    @CircuitBreaker(name = "geminiJournalGenerationClient", fallbackMethod = "fallback")
    override fun generate(request: JournalGenerationRequest): JournalGenerationResponse {
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
            ?: throw JournalGenerationException("Gemini 응답에 유효한 content가 없습니다(안전 필터 등으로 차단됐을 수 있음)")

        val payload = runCatching { jsonMapper.readValue(text, GeminiJournalPayload::class.java) }
            .getOrElse { e -> throw JournalGenerationException("Gemini 응답 JSON 파싱 실패: $text", e) }

        return JournalGenerationResponse(
            generationId = UuidV7.generate(),
            title = payload.title,
            content = jsonMapper.writeValueAsString(JournalContentPayload(body = payload.body)),
            model = response.modelVersion ?: properties.model,
            provider = PROVIDER,
            promptVersion = PROMPT_VERSION,
            inputTokens = response.usageMetadata?.promptTokenCount ?: 0,
            outputTokens = response.usageMetadata?.candidatesTokenCount ?: 0,
        )
    }

    @Suppress("unused")
    fun fallback(request: JournalGenerationRequest, e: Throwable): JournalGenerationResponse {
        log.error("Gemini Journal 생성 실패: model={}, error={}", properties.model, e.message, e)
        throw JournalGenerationException("Gemini Journal 생성 실패", e)
    }
}

// journals.content(JSONB)에 그대로 저장되는 최소 구조 — title은 journals.title 컬럼으로 분리 저장하므로 제외한다.
// 실제 소비처(아카이브 상세 조회)가 생기기 전까지는 이 이상 구조화하지 않는다.
private data class JournalContentPayload(val body: String)

private data class GeminiJournalPayload(val title: String, val body: String)

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
