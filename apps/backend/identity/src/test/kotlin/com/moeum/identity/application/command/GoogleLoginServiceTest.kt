package com.moeum.identity.application.command

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.UserRepository
import com.moeum.identity.domain.model.GoogleProfile
import com.moeum.identity.domain.model.User
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.security.jwt.JwtProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GoogleLoginServiceTest {

    private class FakeGoogleIdTokenVerifier(private val profile: GoogleProfile) : GoogleIdTokenVerifierPort {
        override fun verify(idToken: String): GoogleProfile = profile
    }

    private class FakeUserRepository : UserRepository {
        val users = mutableMapOf<String, User>()
        override fun findByGoogleId(googleId: String): User? = users[googleId]
        override fun save(user: User): User {
            users[user.googleId] = user
            return user
        }
    }

    private class FakeJwtProvider : JwtProvider {
        var issuedFor: UserId? = null
        override fun issue(userId: UserId): String {
            issuedFor = userId
            return "fake-jwt-for-${userId.value}"
        }
        override fun parse(token: String): Nothing = throw UnsupportedOperationException("not used in this test")
    }

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    @Test
    fun `처음 로그인하는 구글 계정은 새 User를 생성하고 JWT를 발급한다`() {
        val profile = GoogleProfile(googleId = "google-1", email = "a@example.com")
        val userRepository = FakeUserRepository()
        val jwtProvider = FakeJwtProvider()
        val service = GoogleLoginService(
            googleIdTokenVerifier = FakeGoogleIdTokenVerifier(profile),
            userRepository = userRepository,
            jwtProvider = jwtProvider,
            timeProvider = FixedTimeProvider(Instant.now()),
        )

        val jwt = service.login("any-id-token")

        assertThat(userRepository.users).containsKey("google-1")
        val createdUser = userRepository.users.getValue("google-1")
        assertThat(jwtProvider.issuedFor).isEqualTo(createdUser.id)
        assertThat(jwt).isEqualTo("fake-jwt-for-${createdUser.id.value}")
    }

    @Test
    fun `이미 가입된 구글 계정은 기존 User로 JWT를 발급한다`() {
        val profile = GoogleProfile(googleId = "google-2", email = "b@example.com")
        val userRepository = FakeUserRepository()
        val existingUser = User.create(
            id = UserId.generate(),
            googleId = "google-2",
            email = "b@example.com",
            now = Instant.now(),
        )
        userRepository.save(existingUser)
        val jwtProvider = FakeJwtProvider()
        val service = GoogleLoginService(
            googleIdTokenVerifier = FakeGoogleIdTokenVerifier(profile),
            userRepository = userRepository,
            jwtProvider = jwtProvider,
            timeProvider = FixedTimeProvider(Instant.now()),
        )

        service.login("any-id-token")

        assertThat(userRepository.users).hasSize(1)
        assertThat(jwtProvider.issuedFor).isEqualTo(existingUser.id)
    }
}
