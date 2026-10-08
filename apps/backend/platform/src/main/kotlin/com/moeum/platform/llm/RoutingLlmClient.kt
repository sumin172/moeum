package com.moeum.platform.llm

import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UuidV7
import com.moeum.platform.llm.ledger.LlmInvocation
import com.moeum.platform.llm.ledger.LlmInvocationLedger
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val DEFAULT_MAX_CONCURRENT_CALLS = 10

// 용도(purpose)별 라우팅 → provider별 동시 호출 상한 → provider 호출(서킷브레이커는 provider에) → 호출 원장 기록.
@Component
class RoutingLlmClient(
    providers: List<LlmProvider>,
    private val properties: LlmProperties,
    private val ledger: LlmInvocationLedger,
    private val timeProvider: TimeProvider,
) : LlmClient {

    private val log = LoggerFactory.getLogger(RoutingLlmClient::class.java)
    private val providersByName = providers.associateBy { it.name }
    private val permits = providersByName.keys.associateWith {
        Semaphore(properties.maxConcurrentCalls[it] ?: DEFAULT_MAX_CONCURRENT_CALLS)
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

        if (!permit.tryAcquire(properties.acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw LlmException("LLM 동시 호출 상한 초과: provider=${route.provider}, purpose=${request.purpose}")
        }
        val startedAt = System.nanoTime()
        try {
            val result = provider.generate(route, request)
            val generationId = UuidV7.generate()
            record(request, provider, route, startedAt, result = result, generationId = generationId, error = null)
            return LlmResult(
                generationId = generationId,
                text = result.text,
                provider = provider.vendor,
                model = result.model,
                promptVersion = request.promptVersion,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens,
            )
        } catch (e: Exception) {
            record(request, provider, route, startedAt, result = null, generationId = null, error = e)
            throw e as? LlmException ?: LlmException("LLM 호출 실패: provider=${route.provider}, purpose=${request.purpose}", e)
        } finally {
            permit.release()
        }
    }

    // 원장 기록 실패가 LLM 호출 결과를 바꾸면 안 된다 — 로그만 남긴다.
    private fun record(
        request: LlmRequest,
        provider: LlmProvider,
        route: LlmRoute,
        startedAt: Long,
        result: LlmProviderResult?,
        generationId: java.util.UUID?,
        error: Exception?,
    ) {
        runCatching {
            ledger.record(
                LlmInvocation(
                    id = UuidV7.generate(),
                    purpose = request.purpose,
                    userId = request.userId,
                    provider = provider.vendor,
                    model = result?.model ?: route.model,
                    promptVersion = request.promptVersion,
                    generationId = generationId,
                    inputTokens = result?.inputTokens ?: 0,
                    outputTokens = result?.outputTokens ?: 0,
                    cachedInputTokens = result?.cachedInputTokens ?: 0,
                    latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                    succeeded = error == null,
                    finishReason = result?.finishReason,
                    errorCode = error?.let { rootCauseName(it) },
                    createdAt = timeProvider.now(),
                ),
            )
        }.onFailure { e -> log.warn("LLM 호출 원장 기록 실패: purpose={}, error={}", request.purpose, e.message) }
    }
}

// 감싼 예외보다 실제 원인(HttpServerErrorException, CallNotPermittedException 등)이 집계에 유용하다
private fun rootCauseName(e: Throwable): String {
    var cause: Throwable = e
    while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
    return cause::class.simpleName ?: "UNKNOWN"
}
