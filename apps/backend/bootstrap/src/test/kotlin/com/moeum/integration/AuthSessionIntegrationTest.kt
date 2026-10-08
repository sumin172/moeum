package com.moeum.integration

import com.moeum.conversation.interfaces.dto.TodayConversationResponse
import com.moeum.identity.interfaces.dto.AuthTokenResponse
import com.moeum.identity.interfaces.dto.RefreshTokenRequest
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.platform.web.ErrorResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.exchange
import org.springframework.boot.resttestclient.postForEntity
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 로그인 → refresh → 로그아웃, 재사용 감지, 동시 refresh, 탈퇴 사용자 차단을 실제 DB·HTTP로 검증한다.
 */
class AuthSessionIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private fun refresh(refreshToken: String): ResponseEntity<String> =
        restTemplate.postForEntity<String>("/api/auth/refresh", RefreshTokenRequest(refreshToken))

    private fun refreshed(refreshToken: String): AuthTokenResponse =
        restTemplate.postForEntity<AuthTokenResponse>("/api/auth/refresh", RefreshTokenRequest(refreshToken)).body!!

    private fun errorCodeOf(response: ResponseEntity<String>): String =
        Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"").find(response.body!!)!!.groupValues[1]

    @Test
    fun `refresh로 받은 새 access token으로 API를 계속 쓸 수 있다`() {
        val tokens = login()

        val next = refreshed(tokens.refreshToken)

        assertThat(next.refreshToken).isNotEqualTo(tokens.refreshToken)
        val response = restTemplate.exchange<TodayConversationResponse>(
            "/api/conversations/today?timezone=Asia/Seoul", HttpMethod.GET, HttpEntity<Void>(authHeaders(next.accessToken)),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `교체된 예전 refresh token이 다시 쓰이면 거부하고 세션 전체를 폐기한다`() {
        val tokens = login()
        val next = refreshed(tokens.refreshToken)
        // 유예 시간(30초)이 지난 것처럼 만든다
        jdbcTemplate.update(
            "UPDATE identity.auth_sessions SET rotated_at = rotated_at - interval '1 minute' WHERE id = ?::uuid",
            tokens.refreshToken.substringBefore('.'),
        )

        val reused = refresh(tokens.refreshToken)
        assertThat(reused.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(errorCodeOf(reused)).isEqualTo("IDENTITY_INVALID_REFRESH_TOKEN")

        // 폐기가 커밋됐으므로 최신 토큰도 더는 쓸 수 없다
        assertThat(refresh(next.refreshToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `같은 refresh token으로 동시에 요청하면 하나만 성공하고 나머지는 이미 교체됐다는 409를 받는다`() {
        val tokens = login()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)

        val statuses = executor.invokeAll(
            (1..4).map {
                Callable {
                    start.await()
                    refresh(tokens.refreshToken).statusCode
                }
            }.also { start.countDown() },
        ).map { it.get() }
        executor.shutdown()

        assertThat(statuses.count { it == HttpStatus.OK }).isEqualTo(1)
        assertThat(statuses.filter { it != HttpStatus.OK }).allMatch { it == HttpStatus.CONFLICT }
    }

    @Test
    fun `로그아웃한 세션의 refresh token은 거부된다`() {
        val tokens = login()

        val logout = restTemplate.postForEntity<Void>("/api/auth/logout", RefreshTokenRequest(tokens.refreshToken))

        assertThat(logout.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(refresh(tokens.refreshToken).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `탈퇴한 사용자는 refresh와 로그인 모두 거부된다`() {
        val idToken = "deleted-${UUID.randomUUID()}"
        val tokens = login(idToken = idToken)
        jdbcTemplate.update("UPDATE identity.users SET deleted_at = now() WHERE google_id = ?", "test-google-$idToken")

        val refreshResponse = refresh(tokens.refreshToken)
        assertThat(refreshResponse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(errorCodeOf(refreshResponse)).isEqualTo("IDENTITY_USER_DELETED")

        val loginResponse = restTemplate.postForEntity<ErrorResponse>(
            "/api/auth/google",
            com.moeum.identity.interfaces.dto.GoogleLoginRequest(idToken = idToken, deviceId = UUID.randomUUID()),
        )
        assertThat(loginResponse.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
