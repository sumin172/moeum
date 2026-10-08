package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.platform.web.ErrorResponse
import com.moeum.platform.web.PlatformErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

/**
 * 클라이언트 요청 형식 오류가 500이 아니라 400 + 공통 에러 포맷으로 응답되는지 검증한다.
 */
class RequestValidationIntegrationTest : AbstractIntegrationTest() {

    private fun jsonHeaders() = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

    @Test
    fun `body에 필수 필드가 빠지면 400을 반환한다`() {
        val response = restTemplate.exchange<ErrorResponse>(
            "/api/auth/google", HttpMethod.POST, HttpEntity("""{"idToken":"x"}""", jsonHeaders()),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!.code).isEqualTo(PlatformErrorCode.INVALID_REQUEST.code)
    }

    @Test
    fun `body가 JSON이 아니거나 필드 형식이 틀리면 400을 반환한다`() {
        val malformed = restTemplate.exchange<ErrorResponse>(
            "/api/auth/google", HttpMethod.POST, HttpEntity("not-json", jsonHeaders()),
        )
        val wrongType = restTemplate.exchange<ErrorResponse>(
            "/api/auth/google", HttpMethod.POST, HttpEntity("""{"idToken":"x","deviceId":"not-a-uuid"}""", jsonHeaders()),
        )

        assertThat(malformed.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(wrongType.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `필수 쿼리 파라미터가 없거나 타입이 틀리면 400을 반환한다`() {
        val jwt = issueJwt()

        val missing = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/today", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        )
        val wrongType = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/today?timezone=Asia/Seoul&after=not-a-uuid", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        )

        assertThat(missing.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(missing.body!!.code).isEqualTo(PlatformErrorCode.INVALID_REQUEST.code)
        assertThat(wrongType.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
