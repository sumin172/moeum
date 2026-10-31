package com.moeum.capacity

import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmProperties
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRole
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.RoutingLlmClient
import com.moeum.platform.llm.provider.gemini.GeminiProperties
import com.moeum.platform.llm.provider.gemini.GeminiProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper

class ProviderFailureSummaryTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `공급자 과부하를 분류하고 오류 본문은 보존하지 않는다`() {
        val headers = HttpHeaders().apply { add("Content-Type", "application/json"); add("Retry-After", "30") }
        val error = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", headers,
            """{"error":{"code":503,"status":"UNAVAILABLE","message":"Model overloaded PRIVATE_INPUT PRIVATE_KEY"}}""".toByteArray(), Charsets.UTF_8)
        val summary = summarizeProviderFailure(error, mapper)
        assertThat(summary["diagnosis"]).isEqualTo("MODEL_CAPACITY")
        assertThat(summary["apiStatus"]).isEqualTo("UNAVAILABLE")
        assertThat(summary["retryAfterSeconds"]).isEqualTo(30L)
        assertThat(mapper.writeValueAsString(summary)).doesNotContain("PRIVATE_INPUT", "PRIVATE_KEY")
    }

    @Test
    fun `JSON API 오류가 아닌 응답은 별도로 구분한다`() {
        val error = HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders(),
            "<html>PRIVATE_PROXY_DETAILS</html>".toByteArray(), Charsets.UTF_8)
        val summary = summarizeProviderFailure(error, mapper)
        assertThat(summary["diagnosis"]).isEqualTo("NON_API_ERROR_BODY")
        assertThat(summary["apiStatus"]).isNull()
        assertThat(mapper.writeValueAsString(summary)).doesNotContain("PRIVATE_PROXY_DETAILS")
    }

    private data class Outcome(val type: Class<*>, val status: Int, val retryAfterHeader: String?, val body: String,
                               val errorCode: String?, val retryAfter: java.time.Duration?)

    // 같은 공급자 오류를 진단 수집 유무만 달리해 공급자 예외와 라우터 변환 결과를 얻는다.
    private fun outcome(status: Int, capture: Boolean, summaries: MutableList<Map<String, Any?>>): Outcome {
        fun server(builder: RestClient.Builder) = MockRestServiceServer.bindTo(builder).build().also {
            it.expect(requestTo("https://gemini.test/v1beta/models/flash:generateContent")).andRespond(
                withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                    .headers(HttpHeaders().apply { if (status == 429) add("Retry-After", "45") })
                    .body("""{"error":{"code":$status,"status":"X","message":"PRIVATE_BODY"}}"""))
        }
        val route = LlmRoute("gemini", "flash", 100)
        val request = LlmRequest("chat", "system", listOf(LlmMessage(LlmRole.USER, "hi")), "v1")
        fun provider(): GeminiProvider {
            val builder = RestClient.builder().baseUrl("https://gemini.test")
            server(builder)
            val client = builder.build()
            return GeminiProvider(if (capture) withProviderFailureCapture(client) { summaries += it } else client, GeminiProperties(apiKey = "k"))
        }
        val raw = runCatching { provider().generate(route, request) }.exceptionOrNull() as RestClientResponseException
        val routed = runCatching {
            RoutingLlmClient(listOf(provider()), LlmProperties(routes = mapOf("chat" to route))) { }.generate(request)
        }.exceptionOrNull() as LlmException
        return Outcome(raw.javaClass, raw.statusCode.value(), raw.responseHeaders?.getFirst("Retry-After"),
            raw.responseBodyAsString, routed.errorCode, routed.retryAfter)
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 429, 503])
    fun `진단 수집은 공급자 예외의 타입·상태·헤더와 재시도 분류를 바꾸지 않는다`(status: Int) {
        val summaries = mutableListOf<Map<String, Any?>>()
        val plain = outcome(status, capture = false, summaries)
        val captured = outcome(status, capture = true, summaries)

        assertThat(captured).isEqualTo(plain)
        if (status == 429) assertThat(captured.retryAfter).isEqualTo(java.time.Duration.ofSeconds(45))
        assertThat(summaries).hasSize(2).allSatisfy { assertThat(it["status"]).isEqualTo(status) }
        assertThat(mapper.writeValueAsString(summaries)).doesNotContain("PRIVATE_BODY")
    }
}
