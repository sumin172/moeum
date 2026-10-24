package com.moeum.capacity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpServerErrorException
import tools.jackson.databind.json.JsonMapper

class JournalProviderDiagnosticsTest {
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
}
