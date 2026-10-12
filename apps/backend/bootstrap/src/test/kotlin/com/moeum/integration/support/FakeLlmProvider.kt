package com.moeum.integration.support

import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult

// 실제 provider를 부르지 않고 RoutingLlmClient → 관측 수집 경로를 검증하기 위한 provider.
// 메시지 내용이 "fail"이면 실패한다. moeum.llm.routes.integration-test가 이 provider를 쓴다.
class FakeLlmProvider : LlmProvider {
    private val journalFailures = java.util.concurrent.ConcurrentHashMap<java.util.UUID,java.util.concurrent.atomic.AtomicInteger>()
    private val journalInputs = java.util.concurrent.ConcurrentHashMap<java.util.UUID,java.util.concurrent.CopyOnWriteArrayList<List<com.moeum.platform.llm.LlmMessage>>>()
    fun failNextJournal(user: java.util.UUID, times: Int) { journalFailures[user] = java.util.concurrent.atomic.AtomicInteger(times) }
    fun inputsFor(user: java.util.UUID): List<List<com.moeum.platform.llm.LlmMessage>> = journalInputs[user]?.toList() ?: emptyList()
    fun reset() { journalFailures.clear(); journalInputs.clear() }
    override val name = "fake"
    override val vendor = "fake-vendor"

    override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
        if (request.purpose == "journal-generation") {
            val user = java.util.UUID.fromString(org.slf4j.MDC.get("recordDayId"))
            journalInputs.computeIfAbsent(user) { java.util.concurrent.CopyOnWriteArrayList() }.add(request.messages.toList())
            if ((journalFailures[user]?.getAndUpdate { if (it>0) it-1 else 0 } ?: 0)>0) throw LlmException("테스트용 일기 실패")
        }
        if (request.messages.any { it.content == "timeout" }) throw java.net.SocketTimeoutException("PRIVATE_BODY_BEARER")
        if (request.messages.any { it.content == "429" }) throw org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "PRIVATE_BODY_BEARER", org.springframework.http.HttpHeaders(),
            "PRIVATE_BODY_BEARER".toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
        if (request.messages.any { it.content == "fail" }) throw LlmException("테스트용 실패")
        // 일기 생성은 JSON 응답을 기대한다
        val text = if (request.messages.any { it.content.contains("invalid-json") }) "PRIVATE_BODY_BEARER" else if (request.purpose == "journal-generation") """{"title":"테스트 일기","body":"오늘의 기록"}""" else "ok"
        return LlmProviderResult(text = text, model = route.model, inputTokens = 7, outputTokens = 3)
    }
}
