package com.moeum.identity.application.command

import com.moeum.identity.domain.model.AuthSessionRevokeReason
import com.moeum.identity.domain.model.User
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
    private val properties = AuthSessionProperties(refreshTokenTtl = Duration.ofDays(30), rotationGrace = Duration.ofSeconds(30))
    private val service = AuthSessionService(sessionRepository, userRepository, FakeJwtProvider(timeProvider), properties, timeProvider)
    private val user = userRepository.save(User.create(UserId.generate(), "google", "a@example.com", timeProvider.now))

    private fun login(): AuthTokens = service.start(user.id, UUID.randomUUID())
    private fun session() = sessionRepository.sessions.values.single()

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

        assertThat(session().revokeReason).isEqualTo(AuthSessionRevokeReason.REFRESH_TOKEN_REUSED)
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
    fun `탈퇴한 사용자는 refresh를 거부하고 세션을 폐기한다`() {
        val first = login()
        userRepository.save(user.copy(deletedAt = timeProvider.now))

        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.UserDeleted)

        assertThat(session().revokeReason).isEqualTo(AuthSessionRevokeReason.USER_DELETED)
    }

    @Test
    fun `로그아웃하면 세션이 폐기되고, 다시 로그아웃해도 성공한다`() {
        val first = login()

        service.logout(first.refreshToken)
        service.logout(first.refreshToken)
        service.logout("garbage")

        assertThat(session().revokeReason).isEqualTo(AuthSessionRevokeReason.LOGOUT)
        assertThat(service.refresh(first.refreshToken)).isEqualTo(RefreshOutcome.Invalid)
    }
}
