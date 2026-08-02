package com.moeum.platform.security.jwt

import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.security.config.JwtProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class JwtProviderTest {

    private val jwtProperties = JwtProperties(
        secret = "test-secret-key-for-jwt-must-be-at-least-32-bytes-long",
        expirationSeconds = 3600,
    )

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    @Test
    fun `발급한 토큰을 파싱하면 원래 claims를 복원한다`() {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val jwtProvider: JwtProvider = JwtProviderImpl(jwtProperties, FixedTimeProvider(now))
        val userId = UserId.generate()

        val token = jwtProvider.issue(userId)
        val parsed = jwtProvider.parse(token)

        assertThat(parsed.userId).isEqualTo(userId)
        assertThat(parsed.issuedAt).isEqualTo(now)
        assertThat(parsed.expiresAt).isEqualTo(now.plusSeconds(jwtProperties.expirationSeconds))
    }

    @Test
    fun `만료된 토큰은 파싱 시 예외를 던진다`() {
        val longAgo = Instant.now().minusSeconds(jwtProperties.expirationSeconds + 3600)
        val jwtProvider: JwtProvider = JwtProviderImpl(jwtProperties, FixedTimeProvider(longAgo))

        val expiredToken = jwtProvider.issue(UserId.generate())

        assertThatThrownBy { jwtProvider.parse(expiredToken) }
            .isInstanceOf(InvalidJwtException::class.java)
    }

    @Test
    fun `서명이 다른 토큰은 파싱 시 예외를 던진다`() {
        val now = Instant.now()
        val jwtProvider: JwtProvider = JwtProviderImpl(jwtProperties, FixedTimeProvider(now))
        val otherProvider: JwtProvider = JwtProviderImpl(
            JwtProperties(secret = "different-secret-key-for-jwt-at-least-32-bytes!!", expirationSeconds = 3600),
            FixedTimeProvider(now),
        )

        val token = otherProvider.issue(UserId.generate())

        assertThatThrownBy { jwtProvider.parse(token) }
            .isInstanceOf(InvalidJwtException::class.java)
    }
}
