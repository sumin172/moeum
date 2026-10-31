package com.moeum.platform.llm.provider.cloudflare

import com.fasterxml.jackson.annotation.JsonProperty
import com.moeum.platform.llm.*
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@Component
class CloudflareProvider(
    private val cloudflareRestClient: RestClient,
    private val properties: CloudflareProperties,
) : LlmProvider {
    override val name = "cloudflare"
    override val vendor = "cloudflare"

    override fun missingSettings(): List<String> = listOfNotNull(
        "moeum.cloudflare.account-id".takeIf { properties.accountId.isBlank() },
        "moeum.cloudflare.api-token".takeIf { properties.apiToken.isBlank() },
    )

    @CircuitBreaker(name = "llm-cloudflare")
    override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
        // 기동 시 라우트 검사를 거치지 않은 호출도 원격 요청 없이 실패시킨다. 필수 설정 판단은 missingSettings 하나로 둔다.
        if (missingSettings().isNotEmpty()) {
            throw LlmException("Cloudflare 인증 설정이 없습니다", errorCode = "HTTP_401")
        }
        require(request.responseFormat == LlmResponseFormat.TEXT) { "Cloudflare 대화 경로는 TEXT만 지원합니다" }
        require(route.thinkingBudget == null || route.thinkingBudget == 0) { "Cloudflare thinking 토큰 예산은 지원하지 않습니다" }
        // Cloudflare에 문서화된 hard switch가 없어 Qwen의 soft switch를 마지막 USER에 적용한다.
        val lastUser = request.messages.indexOfLast { it.role == LlmRole.USER }
        val messages = listOf(CloudflareMessage("system", request.systemPrompt)) +
            request.messages.mapIndexed { index, message ->
                CloudflareMessage(
                    if (message.role == LlmRole.USER) "user" else "assistant",
                    message.content + if (route.thinkingBudget == 0 && index == lastUser) "\n/no_think" else "",
                )
            }
        val response = cloudflareRestClient.post()
            .uri("/client/v4/accounts/{account}/ai/v1/chat/completions", properties.accountId)
            .header("Authorization", "Bearer ${properties.apiToken}")
            .body(CloudflareRequest(route.model, messages, route.maxOutputTokens))
            .retrieve().body<CloudflareResponse>()
            ?: throw LlmException("Cloudflare 응답 본문이 비어 있습니다")
        val choice = response.choices.orEmpty().firstOrNull()
        val raw = choice?.message?.content.orEmpty()
        // 완결된 선두 추론 블록만 제거한다. 잘리거나 중간에 섞인 추론은 저장하지 않는다.
        val text = raw.trimStart().replaceFirst(Regex("^<think>[\\s\\S]*?</think>\\s*"), "")
        return LlmProviderResult(
            text = text,
            model = response.model ?: route.model,
            inputTokens = response.usage?.promptTokens ?: 0,
            outputTokens = response.usage?.completionTokens ?: 0,
            cachedInputTokens = response.usage?.promptTokensDetails?.cachedTokens ?: 0,
            finishReason = choice?.finishReason,
            errorCode = when {
                choice?.finishReason == "length" -> "OUTPUT_TRUNCATED"
                choice?.finishReason == "content_filter" -> "OUTPUT_BLOCKED"
                choice?.finishReason != "stop" -> "UNEXPECTED_FINISH_REASON"
                "<think>" in text || "</think>" in text -> "UNEXPECTED_FINISH_REASON"
                text.isBlank() -> "EMPTY_CONTENT"
                else -> null
            },
        )
    }
}

data class CloudflareMessage(val role: String, val content: String)
data class CloudflareRequest(
    val model: String,
    val messages: List<CloudflareMessage>,
    @get:JsonProperty("max_tokens") val maxTokens: Int,
    val stream: Boolean = false,
)
data class CloudflareResponse(
    val model: String? = null,
    val choices: List<CloudflareChoice>? = null,
    val usage: CloudflareUsage? = null,
)
data class CloudflareChoice(
    val message: CloudflareOutputMessage? = null,
    @param:JsonProperty("finish_reason") val finishReason: String? = null,
)
data class CloudflareOutputMessage(val content: String? = null)
data class CloudflareUsage(
    @param:JsonProperty("prompt_tokens") val promptTokens: Int? = null,
    @param:JsonProperty("completion_tokens") val completionTokens: Int? = null,
    @param:JsonProperty("prompt_tokens_details") val promptTokensDetails: CloudflareTokenDetails? = null,
)
data class CloudflareTokenDetails(@param:JsonProperty("cached_tokens") val cachedTokens: Int? = null)
