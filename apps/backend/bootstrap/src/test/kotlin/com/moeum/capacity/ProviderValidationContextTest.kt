package com.moeum.capacity

import com.moeum.journal.infrastructure.ai.JOURNAL_GENERATION_PURPOSE
import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmResponseFormat
import com.moeum.platform.llm.LlmRole
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration

/** 실제 공급자 없이 로컬 HTTP 응답으로 검증 실행 기반의 등록·라우팅·인증 검사·오류 보존을 확인한다. */
class ProviderValidationContextTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @Volatile private var status = 200
    @Volatile private var body = ""
    @Volatile private var retryAfter: String? = null

    init {
        server.createContext("/") { exchange ->
            retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach fun stop() = server.stop(0)

    // 일기 워커 프로필과 운영 라우트를 쓰되 Gemini 연결만 로컬 서버로 바꾼다. 로컬 프로필의 키는 읽지 않는다.
    private fun journalContext(apiKey: String = "test-key") = ProviderValidationContext.start("journal-worker", mapOf(
        "moeum.gemini.base-url" to "http://127.0.0.1:${server.address.port}",
        "moeum.gemini.api-key" to apiKey,
        "moeum.cloudflare.account-id" to "",
        "moeum.cloudflare.api-token" to "",
    ), includeLocal = false)

    private val request = LlmRequest(JOURNAL_GENERATION_PURPOSE, "system", listOf(LlmMessage(LlmRole.USER, "synthetic")),
        "context-test-v1", LlmResponseFormat.JSON)

    @Test
    fun `운영 등록과 라우팅으로 선택한 경로만 인증을 검사하고 정상 응답을 관측한다`() {
        body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"title\":\"t\",\"body\":\"b\"}"}]},"finishReason":"STOP"}],
            "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3},"modelVersion":"gemini-test-001"}"""
        journalContext().use { context ->
            // 일기 경로만 검사하므로 이 역할이 쓰지 않는 Cloudflare 인증이 비어 있어도 된다
            context.client.requireRoute(JOURNAL_GENERATION_PURPOSE)
            assertThat(context.route(JOURNAL_GENERATION_PURPOSE).provider).isEqualTo("gemini")
            val observed = context.observe { context.client.generate(request) }
            assertThat(observed.result.getOrThrow().model).isEqualTo("gemini-test-001")
            assertThat(observed.events.single().succeeded).isTrue()
            assertThat(observed.httpFailure).isNull()
            assertThat(context.providerSettings("gemini")).containsKey("readTimeoutMs").doesNotContainKey("apiKey")
        }
        journalContext(apiKey = "").use { context ->
            assertThatThrownBy { context.client.requireRoute(JOURNAL_GENERATION_PURPOSE) }.hasMessageContaining("moeum.gemini.api-key")
        }
    }

    @Test
    fun `공급자 오류의 재시도 분류와 Retry-After를 보존하고 본문 없이 분류만 기록한다`() {
        status = 429
        retryAfter = "30"
        body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"PRIVATE_INPUT quota"}}"""
        journalContext().use { context ->
            val observed = context.observe { context.client.generate(request) }
            val error = observed.result.exceptionOrNull() as LlmException
            assertThat(error.errorCode).isEqualTo("HTTP_429")
            assertThat(error.retryAfter).isEqualTo(Duration.ofSeconds(30))
            assertThat(observed.httpFailure).containsEntry("status", 429).containsEntry("retryAfterSeconds", 30L)
            assertThat(observed.httpFailure.toString()).doesNotContain("PRIVATE_INPUT")
            assertThat(observed.events.single().errorCode).isEqualTo("HTTP_429")
        }
    }
}
