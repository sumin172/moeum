package com.moeum.platform.llm.journal.claude

import com.fasterxml.jackson.annotation.JsonProperty
import com.moeum.kernel.UuidV7
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

private const val PROVIDER = "anthropic"
private const val PROMPT_VERSION = "v1"

// 자유 서술이 아니라 JSON 객체만 반환하도록 강제한다(Claude는 Gemini의 responseMimeType 같은 강제 옵션이
// 없어 시스템 지시로만 제어한다).
private const val SYSTEM_INSTRUCTION =
    "당신은 사용자의 하루 대화 원본을 바탕으로 1인칭 일기를 쓰는 작가입니다. " +
        "대화에 실제로 등장한 내용만 사용하고, 없는 내용을 지어내지 마세요. " +
        "오직 JSON 객체만 응답하세요. {\"title\": string, \"body\": string} 형태입니다. " +
        "title은 그날을 대표하는 짧은 한국어 제목, body는 자연스러운 한국어 1인칭 일기 서술입니다."

// moeum.journal.provider=claude일 때만 활성화된다(기본값은 GeminiJournalGenerator, 결제 설정 전까지의 임시 선택).
@Component
@ConditionalOnProperty(name = ["moeum.journal.provider"], havingValue = "claude")
class ClaudeJournalGenerator(
    private val claudeRestClient: RestClient,
    private val properties: ClaudeProperties,
    private val jsonMapper: JsonMapper,
) : JournalGenerator {

    private val log = LoggerFactory.getLogger(ClaudeJournalGenerator::class.java)

    // recordExceptions(application.yml)이 실제 예외 타입을 보고 서킷 개폐를 판단하므로,
    // 이 메서드 안에서 예외를 감싸지 않고 그대로 전파시킨다 — 정규화는 fallback에서만 한다.
    @CircuitBreaker(name = "claudeJournalGenerationClient", fallbackMethod = "fallback")
    override fun generate(request: JournalGenerationRequest): JournalGenerationResponse {
        val requestBody = ClaudeMessageRequest(
            model = properties.model,
            maxTokens = properties.maxTokens,
            system = SYSTEM_INSTRUCTION,
            messages = listOf(ClaudeMessage(role = "user", content = request.rawTranscript)),
        )

        val response = claudeRestClient.post()
            .uri("/v1/messages")
            .header("x-api-key", properties.apiKey)
            .header("anthropic-version", properties.apiVersion)
            .body(requestBody)
            .retrieve()
            .body<ClaudeMessageResponse>()

        val text = response?.content?.firstOrNull { it.type == "text" }?.text
            ?: throw JournalGenerationException("Claude 응답에 유효한 content가 없습니다")

        val payload = runCatching { jsonMapper.readValue(text, ClaudeJournalPayload::class.java) }
            .getOrElse { e -> throw JournalGenerationException("Claude 응답 JSON 파싱 실패: $text", e) }

        return JournalGenerationResponse(
            generationId = UuidV7.generate(),
            title = payload.title,
            content = jsonMapper.writeValueAsString(JournalContentPayload(body = payload.body)),
            model = response.model ?: properties.model,
            provider = PROVIDER,
            promptVersion = PROMPT_VERSION,
            inputTokens = response.usage?.inputTokens ?: 0,
            outputTokens = response.usage?.outputTokens ?: 0,
        )
    }

    @Suppress("unused")
    fun fallback(request: JournalGenerationRequest, e: Throwable): JournalGenerationResponse {
        log.error("Claude Journal 생성 실패: model={}, error={}", properties.model, e.message, e)
        throw JournalGenerationException("Claude Journal 생성 실패", e)
    }
}

// journals.content(JSONB)에 그대로 저장되는 최소 구조 — title은 journals.title 컬럼으로 분리 저장하므로 제외한다.
// 실제 소비처(아카이브 상세 조회)가 생기기 전까지는 이 이상 구조화하지 않는다.
private data class JournalContentPayload(val body: String)

private data class ClaudeJournalPayload(val title: String, val body: String)

data class ClaudeMessageRequest(
    val model: String,
    @JsonProperty("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ClaudeMessage>,
)

data class ClaudeMessage(val role: String, val content: String)

data class ClaudeMessageResponse(
    val content: List<ClaudeContentBlock> = emptyList(),
    val model: String? = null,
    val usage: ClaudeUsage? = null,
)

data class ClaudeContentBlock(val type: String, val text: String? = null)

data class ClaudeUsage(
    @JsonProperty("input_tokens") val inputTokens: Int = 0,
    @JsonProperty("output_tokens") val outputTokens: Int = 0,
)
