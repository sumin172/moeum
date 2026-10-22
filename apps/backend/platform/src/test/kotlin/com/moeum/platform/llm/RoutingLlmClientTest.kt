package com.moeum.platform.llm

import com.moeum.platform.observability.LlmCallEvent
import com.moeum.platform.observability.LlmTelemetry
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

class RoutingLlmClientTest {

    private class FakeProvider(override val name: String, private val behavior: (LlmRoute) -> LlmProviderResult) : LlmProvider {
        override val vendor = "$name-vendor"
        val calls = mutableListOf<LlmRoute>()
        override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
            calls += route
            return behavior(route)
        }
    }

    private class InMemoryTelemetry : LlmTelemetry {
        val invocations = java.util.concurrent.CopyOnWriteArrayList<LlmCallEvent>()
        override fun record(event: LlmCallEvent) {
            invocations += event
        }
    }

    private val telemetry = InMemoryTelemetry()

    private fun ok(route: LlmRoute) = LlmProviderResult("답변", "${route.model}-001", 10, 5, cachedInputTokens = 3)

    private fun properties(vararg routes: Pair<String, LlmRoute>, maxConcurrent: Map<String, Int> = emptyMap()) =
        LlmProperties(routes = routes.toMap(), maxConcurrentCalls = maxConcurrent, acquireTimeout = Duration.ofMillis(100))

    private fun request(purpose: String) =
        LlmRequest(purpose, "system", listOf(LlmMessage(LlmRole.USER, "안녕")), promptVersion = "v1")

    @Test
    fun `용도에 지정된 provider와 모델로 호출하고 성공을 관측에 남긴다`() {
        val gemini = FakeProvider("gemini", ::ok)
        val claude = FakeProvider("claude", ::ok)
        val client = RoutingLlmClient(
            listOf(gemini, claude),
            properties("chat" to LlmRoute("gemini", "flash", 150), "diary" to LlmRoute("claude", "haiku", 2000)),
            telemetry,
        )

        val result = client.generate(request("diary"))

        assertThat(gemini.calls).isEmpty()
        assertThat(claude.calls.single().model).isEqualTo("haiku")
        assertThat(result.model).isEqualTo("haiku-001")
        val invocation = telemetry.invocations.single()
        assertThat(invocation.purpose).isEqualTo("diary")
        assertThat(invocation.succeeded).isTrue()
        assertThat(invocation.generationId).isNotNull()
        assertThat(invocation.promptVersion).isEqualTo("v1")
        assertThat(invocation.cachedInputTokens).isEqualTo(3)
    }

    @Test
    fun `실패하면 근본 원인과 함께 관측에 남기고 LlmException으로 던진다`() {
        val failing = FakeProvider("gemini") { throw IllegalStateException("boom", java.io.IOException("connection reset")) }
        val client = RoutingLlmClient(listOf(failing), properties("chat" to LlmRoute("gemini", "flash", 150)), telemetry)

        assertThatThrownBy { client.generate(request("chat")) }.isInstanceOf(LlmException::class.java)

        val invocation = telemetry.invocations.single()
        assertThat(invocation.succeeded).isFalse()
        assertThat(invocation.errorCode).isEqualTo("IO_ERROR")
        assertThat(invocation.generationId).isNotNull()
    }

    @Test
    fun `관측 기록이 실패해도 LLM 호출 결과는 그대로 돌려준다`() {
        val brokenTelemetry = LlmTelemetry { throw IllegalStateException("collector down") }
        val client = RoutingLlmClient(
            listOf(FakeProvider("gemini", ::ok)), properties("chat" to LlmRoute("gemini", "flash", 150)), brokenTelemetry,
        )

        assertThat(client.generate(request("chat")).text).isEqualTo("답변")
    }

    @Test
    fun `provider별 동시 호출 상한을 넘으면 기다리다 LlmException으로 거절한다`() {
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = FakeProvider("gemini") { route ->
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
            ok(route)
        }
        val client = RoutingLlmClient(
            listOf(slow), properties("chat" to LlmRoute("gemini", "flash", 150), maxConcurrent = mapOf("gemini" to 1)), telemetry,
        )
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<LlmResult> { client.generate(request("chat")) }
        inFlight.await(5, TimeUnit.SECONDS)

        assertThatThrownBy { client.generate(request("chat")) }
            .isInstanceOf(LlmException::class.java)
            .hasMessageContaining("동시 호출 상한")

        release.countDown()
        assertThat(first.get(5, TimeUnit.SECONDS).text).isEqualTo("답변")
        executor.shutdown()
    }

    @Test
    fun `없는 provider를 가리키는 라우팅은 기동 시점에 거부한다`() {
        assertThatThrownBy {
            RoutingLlmClient(listOf(FakeProvider("gemini", ::ok)), properties("chat" to LlmRoute("openai", "gpt", 100)), telemetry)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("openai")
    }

    @Test
    fun `라우팅이 없는 용도는 거부한다`() {
        val client = RoutingLlmClient(listOf(FakeProvider("gemini", ::ok)), properties(), telemetry)

        assertThatThrownBy { client.generate(request("unknown")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `429 응답의 Retry-After는 본문 없이 작업 예약에 전달한다`() {
        val headers = HttpHeaders().apply { add("Retry-After", "180") }
        val provider = FakeProvider("gemini") {
            throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate limit", headers,
                "PRIVATE_BODY".toByteArray(), Charsets.UTF_8)
        }
        val client = RoutingLlmClient(listOf(provider), properties("chat" to LlmRoute("gemini", "flash", 150)), telemetry)

        val error = org.assertj.core.api.Assertions.catchThrowable { client.generate(request("chat")) } as LlmException
        assertThat(error.errorCode).isEqualTo("HTTP_429")
        assertThat(error.retryAfter).isEqualTo(Duration.ofSeconds(180))
        assertThat(error.message).doesNotContain("PRIVATE_BODY")
        assertThat(error.cause).isNull()
    }
}
