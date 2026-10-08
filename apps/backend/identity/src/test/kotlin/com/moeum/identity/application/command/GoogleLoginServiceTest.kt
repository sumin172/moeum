package com.moeum.identity.application.command

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.UserDeletedException
import com.moeum.identity.domain.model.GoogleProfile
import com.moeum.identity.domain.model.User
import com.moeum.identity.infrastructure.config.AuthSessionProperties
import com.moeum.identity.support.FakeJwtProvider
import com.moeum.identity.support.InMemoryAuthSessionRepository
import com.moeum.identity.support.InMemoryUserRepository
import com.moeum.identity.support.MutableTimeProvider
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GoogleLoginServiceTest {

    private val timeProvider = MutableTimeProvider(Instant.parse("2026-10-08T00:00:00Z"))
    private val userRepository = InMemoryUserRepository()
    private val sessionRepository = InMemoryAuthSessionRepository()
    private val deviceId = UUID.randomUUID()

    private fun service(profile: GoogleProfile) = GoogleLoginService(
        googleIdTokenVerifier = object : GoogleIdTokenVerifierPort {
            override fun verify(idToken: String): GoogleProfile = profile
        },
        userRepository = userRepository,
        authSessionService = AuthSessionService(
            sessionRepository, userRepository, FakeJwtProvider(timeProvider), AuthSessionProperties(), timeProvider,
        ),
        timeProvider = timeProvider,
    )

    @Test
    fun `처음 로그인하는 구글 계정은 새 User를 만들고 기기 세션과 토큰을 발급한다`() {
        val tokens = service(GoogleProfile("google-1", "a@example.com")).login("id-token", deviceId)

        val user = userRepository.users.values.single()
        val session = sessionRepository.sessions.values.single()
        assertThat(session.userId).isEqualTo(user.id)
        assertThat(session.deviceId).isEqualTo(deviceId)
        assertThat(tokens.accessToken).contains(user.id.value.toString())
        assertThat(tokens.refreshToken).startsWith("${session.id.value}.")
        // DB에는 비밀값이 아니라 해시만 남는다
        assertThat(session.refreshTokenHash).isNotEqualTo(tokens.refreshToken.substringAfter('.'))
    }

    @Test
    fun `이미 가입된 구글 계정은 기존 User로 로그인한다`() {
        val existing = userRepository.save(User.create(UserId.generate(), "google-2", "b@example.com", timeProvider.now))

        service(GoogleProfile("google-2", "b@example.com")).login("id-token", deviceId)

        assertThat(userRepository.users).hasSize(1)
        assertThat(sessionRepository.sessions.values.single().userId).isEqualTo(existing.id)
    }

    @Test
    fun `같은 기기에서 다시 로그인하면 이전 세션을 폐기하고 새 세션을 연다`() {
        val service = service(GoogleProfile("google-3", "c@example.com"))
        val first = service.login("id-token", deviceId)
        service.login("id-token", deviceId)

        val sessions = sessionRepository.sessions.values.toList()
        assertThat(sessions).hasSize(2)
        assertThat(sessions.first().revokedAt).isNotNull()
        assertThat(sessions.last().revokedAt).isNull()
        assertThat(first.refreshToken).startsWith("${sessions.first().id.value}.")
    }

    @Test
    fun `탈퇴한 사용자는 로그인할 수 없다`() {
        userRepository.save(
            User.create(UserId.generate(), "google-4", "d@example.com", timeProvider.now).copy(deletedAt = timeProvider.now),
        )

        assertThatThrownBy { service(GoogleProfile("google-4", "d@example.com")).login("id-token", deviceId) }
            .isInstanceOf(UserDeletedException::class.java)
        assertThat(sessionRepository.sessions).isEmpty()
    }
}
