package com.moeum.platform.llm

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import com.moeum.kernel.UuidV7
import com.moeum.platform.observability.LlmCallEvent
import com.moeum.platform.observability.LlmTelemetry
import com.moeum.platform.observability.SafeFailure
import com.moeum.platform.observability.WorkTrace
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.springframework.web.client.RestClientResponseException

// 용도(purpose)별 라우팅 → provider별 동시 호출 상한 → provider 호출(서킷브레이커는 provider에) → 비차단 관측 기록.
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@Component
class RoutingLlmClient(
    providers: List<LlmProvider>,
    private val properties: LlmProperties,
    private val telemetry: LlmTelemetry,
) : LlmClient {

    private val providersByName = providers.associateBy { it.name }
    private val permits = providersByName.keys.associateWith {
        Semaphore(properties.concurrentLimit(it))
    }

    init {
        // 잘못된 라우팅 설정은 첫 호출이 아니라 기동 시점에 드러나야 한다.
        properties.routes.forEach { (purpose, route) ->
            require(route.provider in providersByName) {
                "moeum.llm.routes.$purpose.provider=${route.provider}: 없는 provider입니다(가능: ${providersByName.keys})"
            }
        }
    }

    override fun generate(request: LlmRequest): LlmResult {
        val route = properties.routes[request.purpose]
            ?: throw IllegalArgumentException("moeum.llm.routes.${request.purpose} 설정이 없습니다")
        val provider = providersByName.getValue(route.provider)
        val permit = permits.getValue(route.provider)

        val generationId = UuidV7.generate()
        WorkTrace.generation(generationId)
        val startedAt = System.nanoTime()
        if (!permit.tryAcquire(properties.acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            val error = LlmException("LLM 동시 호출 상한 초과", errorCode = "BULKHEAD_FULL")
            record(request, provider, route, startedAt, null, generationId, error)
            throw error
        }
        var result: LlmProviderResult? = null
        try {
            result = provider.generate(route, request)
            result.errorCode?.let { throw LlmException("LLM 출력이 정상 완료되지 않았습니다", errorCode = it) }
            record(request, provider, route, startedAt, result = result, generationId = generationId, error = null)
            return LlmResult(
                text = result.text,
                model = result.model,
            )
        } catch (e: Exception) {
            record(request, provider, route, startedAt, result = result, generationId = generationId, error = e)
            // 외부 예외의 응답 본문·URL·인증 정보가 업무 예외와 로그로 전파되지 않게 한다.
            throw LlmException("LLM 호출 실패",
                errorCode = if (e is LlmException) e.errorCode else SafeFailure.code(e),
                retryAfter = retryAfter(e))
        } finally {
            permit.release()
        }
    }

    private fun record(
        request: LlmRequest,
        provider: LlmProvider,
        route: LlmRoute,
        startedAt: Long,
        result: LlmProviderResult?,
        generationId: java.util.UUID,
        error: Exception?,
    ) {
        // 관측 구현이 실패해도 결과나 작업 재시도 판정은 바뀌지 않는다.
        runCatching {
            telemetry.record(LlmCallEvent(
                purpose = request.purpose,
                promptVersion = request.promptVersion,
                generationId = generationId,
                provider = provider.vendor,
                model = result?.model ?: route.model,
                latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                succeeded = error == null,
                errorCode = error?.let(SafeFailure::code),
                inputTokens = result?.inputTokens ?: 0,
                outputTokens = result?.outputTokens ?: 0,
                cachedInputTokens = result?.cachedInputTokens ?: 0,
                finishReason = result?.finishReason?.let { if (it in setOf("STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "end_turn", "max_tokens", "stop_sequence", "refusal", "model_context_window_exceeded")) it else "OTHER" },
            ))
        }
    }
}

private fun retryAfter(error: Exception): Duration? {
    val response = error as? RestClientResponseException ?: return null
    if (response.statusCode.value() != 429) return null
    val value = response.responseHeaders?.getFirst("Retry-After")?.trim() ?: return null
    value.toLongOrNull()?.takeIf { it >= 0 }?.let { return Duration.ofSeconds(it) }
    return runCatching {
        Duration.between(java.time.Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
    }.getOrNull()?.takeIf { !it.isNegative }
}
