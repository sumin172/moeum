package com.moeum.identity.application.command

import com.moeum.identity.domain.model.User
import com.moeum.identity.infrastructure.security.RefreshTokenCodec
import com.moeum.identity.infrastructure.config.AuthSessionProperties
import com.moeum.identity.support.FakeJwtProvider
import com.moeum.identity.support.InMemoryAuthSessionRepository
import com.moeum.identity.support.InMemoryUserRepository
import com.moeum.identity.support.MutableTimeProvider
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AuthSessionServiceTest {

    private val timeProvider = MutableTimeProvider(Instant.parse("2026-10-08T00:00:00Z"))
    private val userRepository = InMemoryUserRepository()
    private val sessionRepository = InMemoryAuthSessionRepository()
    private val properties = AuthSessionProperties(signingSecret = "test-refresh-secret-at-least-32-bytes", refreshTokenTtl = Duration.ofDays(30), rotationGrace = Duration.ofSeconds(30))
    private val service = AuthSessionService(sessionRepository, userRepository, FakeJwtProvider(timeProvider), properties, timeProvider, RefreshTokenCodec(properties))
    private val user = userRepository.save(User.create(UserId.generate(), "google", "a@example.com", timeProvider.now))

    private fun login(): AuthTokens = service.start(user.id, UUID.randomUUID())
    private fun session() = sessionRepository.sessions.values.single()

    @Test
    fun `기존 세션 ID에 임의 비밀값이나 변조 토큰을 제시해도 세션은 변경하지 않는다`() {
        val first = login()
        val initial = session()
        val parts = first.refreshToken.split('.')
        val secret = (if (parts[1][0] == 'A') "B" else "A") + parts[1].drop(1)
        listOf("${initial.id.value}.arbitrary", "${parts[0]}.$secret.${parts[2]}").forEach { invalid ->
            assertThat(service.refresh(invalid)).isEqualTo(RefreshOutcome.Invalid)
            service.logout(invalid)
            assertThat(session()).isEqualTo(initial)
        }
        assertThat(service.refresh(first.refreshToken)).isInstanceOf(RefreshOutcome.Refreshed::class.java)
    }

    @Test
    fun `두 세대 이상 지난 진짜 토큰은 직전 토큰 유예 시간 안에도 재사용으로 폐기한다`() {
        val first = login()
        val second = (service.refresh(first.refreshToken) as RefreshOutcome.Refreshed).tokens
        val third = (service.refresh(second.refreshToken) as RefreshOutcome.Refreshed).tokens
        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.Invalid)
        assertThat(session().revokedAt).isNotNull()
        assertThat(service.refresh(third.refreshToken)).isEqualTo(RefreshOutcome.Invalid)
    }

    @Test
    fun `로그아웃은 유예 시간 내 직전 토큰도 허용한다`() {
        val first = login()
        service.refresh(first.refreshToken)
        timeProvider.advance(Duration.ofSeconds(29))
        service.logout(first.refreshToken)
        assertThat(session().revokedAt).isEqualTo(timeProvider.now)
    }

    @Test
    fun `로그아웃은 직전 토큰의 유예 경계부터 세션을 변경하지 않는다`() {
        val first = login()
        val second = (service.refresh(first.refreshToken) as RefreshOutcome.Refreshed).tokens
        val initial = session()
        timeProvider.advance(Duration.ofSeconds(30))
        service.logout(first.refreshToken)
        assertThat(session()).isEqualTo(initial)
        service.logout(second.refreshToken)
        assertThat(session().revokedAt).isNotNull()
    }

    @Test
    fun `두 세대 이상 지난 토큰으로 로그아웃해도 세션은 유지한다`() {
        val first = login()
        val second = (service.refresh(first.refreshToken) as RefreshOutcome.Refreshed).tokens
        service.refresh(second.refreshToken)
        val initial = session()
        service.logout(first.refreshToken)
        assertThat(session()).isEqualTo(initial)
    }

    @Test
    fun `refresh하면 새 access·refresh token을 주고 만료를 30일 뒤로 연장한다`() {
        val first = login()
        timeProvider.advance(Duration.ofDays(10))

        val refreshed = service.refresh(first.refreshToken) as RefreshOutcome.Refreshed

        assertThat(refreshed.tokens.refreshToken).isNotEqualTo(first.refreshToken)
        assertThat(refreshed.tokens.refreshTokenExpiresAt).isEqualTo(timeProvider.now.plus(Duration.ofDays(30)))
        assertThat(service.refresh(refreshed.tokens.refreshToken)).isInstanceOf(RefreshOutcome.Refreshed::class.java)
    }

    @Test
    fun `교체된 예전 토큰이 유예 시간 뒤에 다시 쓰이면 탈취로 보고 세션 전체를 폐기한다`() {
        val first = login()
        val refreshed = service.refresh(first.refreshToken) as RefreshOutcome.Refreshed
        timeProvider.advance(Duration.ofMinutes(1))

        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.Invalid)

        assertThat(session().revokedAt).isNotNull()
        // 정상 사용자가 가진 최신 토큰도 더는 쓸 수 없다 — 다시 로그인해야 한다
        assertThat(service.refresh(refreshed.tokens.refreshToken)).isEqualTo(RefreshOutcome.Invalid)
    }

    @Test
    fun `동시 요청으로 직전 토큰이 유예 시간 안에 다시 오면 세션을 유지하고 이미 교체됐다고 알린다`() {
        val first = login()
        val refreshed = service.refresh(first.refreshToken) as RefreshOutcome.Refreshed
        timeProvider.advance(Duration.ofSeconds(5))

        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.AlreadyRotated)

        assertThat(session().revokedAt).isNull()
        assertThat(service.refresh(refreshed.tokens.refreshToken)).isInstanceOf(RefreshOutcome.Refreshed::class.java)
    }

    @Test
    fun `만료된 토큰과 형식이 틀린 토큰은 거부한다`() {
        val first = login()

        assertThat(service.refresh("not-a-token")).isEqualTo(RefreshOutcome.Invalid)
        assertThat(service.refresh("${UUID.randomUUID()}.secret")).isEqualTo(RefreshOutcome.Invalid)

        timeProvider.advance(Duration.ofDays(31))
        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.Invalid)
    }

    @Test
    fun `로그아웃하면 세션이 폐기되고, 다시 로그아웃해도 성공한다`() {
        val first = login()

        service.logout(first.refreshToken)
        service.logout(first.refreshToken)
        service.logout("garbage")

        assertThat(session().revokedAt).isNotNull()
        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.Invalid)
    }
}
