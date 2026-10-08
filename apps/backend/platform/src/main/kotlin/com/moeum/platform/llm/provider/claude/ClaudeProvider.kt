package com.moeum.platform.llm.provider.claude

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRole
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

// 결제 설정 전이라 실제 호출은 아직 검증하지 않았다(라우팅에서 claude를 고르는 용도가 없으면 호출되지 않는다).
// JSON 응답 강제 옵션이 없어, LlmResponseFormat.JSON은 프롬프트 지시에 맡긴다.
@Component
class ClaudeProvider(
    private val claudeRestClient: RestClient,
    private val properties: ClaudeProperties,
) : LlmProvider {

    override val name = "claude"
    override val vendor = "anthropic"

    @CircuitBreaker(name = "llm-claude")
    override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
        val body = ClaudeMessageRequest(
            model = route.model,
            maxTokens = route.maxOutputTokens,
            system = request.systemPrompt,
            messages = request.messages.map {
                ClaudeMessage(role = if (it.role == LlmRole.ASSISTANT) "assistant" else "user", content = it.content)
            },
            // extended thinking은 예산이 양수일 때만 켠다(0 또는 null이면 끔)
            thinking = route.thinkingBudget?.takeIf { it > 0 }?.let { ClaudeThinking(budgetTokens = it) },
        )

        val response = claudeRestClient.post()
            .uri("/v1/messages")
            .header("x-api-key", properties.apiKey)
            .header("anthropic-version", properties.apiVersion)
            .body(body)
            .retrieve()
            .body<ClaudeMessageResponse>()
            ?: throw LlmException("Claude 응답 본문이 비어 있습니다")

        val text = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
        if (text.isBlank()) {
            throw LlmException("Claude 응답에 유효한 content가 없습니다: stopReason=${response.stopReason}")
        }

        return LlmProviderResult(
            text = text,
            model = response.model ?: route.model,
            inputTokens = response.usage?.inputTokens ?: 0,
            outputTokens = response.usage?.outputTokens ?: 0,
            cachedInputTokens = response.usage?.cacheReadInputTokens ?: 0,
            finishReason = response.stopReason,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ClaudeMessageRequest(
    val model: String,
    @JsonProperty("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ClaudeMessage>,
    val thinking: ClaudeThinking? = null,
)

data class ClaudeMessage(val role: String, val content: String)

data class ClaudeThinking(
    val type: String = "enabled",
    @JsonProperty("budget_tokens") val budgetTokens: Int,
)

data class ClaudeMessageResponse(
    val content: List<ClaudeContentBlock> = emptyList(),
    val model: String? = null,
    @JsonProperty("stop_reason") val stopReason: String? = null,
    val usage: ClaudeUsage? = null,
)

data class ClaudeContentBlock(val type: String, val text: String? = null)

data class ClaudeUsage(
    @JsonProperty("input_tokens") val inputTokens: Int = 0,
    @JsonProperty("output_tokens") val outputTokens: Int = 0,
    @JsonProperty("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
)
