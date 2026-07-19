package com.moeum.platform.security

import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class JwtProviderTest {

    private val jwtProperties = JwtProperties(
        secret = "test-secret-key-for-jwt-must-be-at-least-32-bytes-long",
        expirationSeconds = 3600,
    )
    private val jwtProvider: JwtProvider = JwtProviderImpl(jwtProperties)

    @Test
    fun `발급한 토큰을 파싱하면 원래 claims를 복원한다`() {
        val userId = UserId.generate()
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val claims = JwtClaims(
            userId = userId,
            subscriptionTier = "FREE",
            issuedAt = now,
            expiresAt = now.plusSeconds(jwtProperties.expirationSeconds),
        )

        val token = jwtProvider.issue(claims)
        val parsed = jwtProvider.parse(token)

        assertThat(parsed.userId).isEqualTo(userId)
        assertThat(parsed.subscriptionTier).isEqualTo("FREE")
        assertThat(parsed.issuedAt).isEqualTo(claims.issuedAt)
        assertThat(parsed.expiresAt).isEqualTo(claims.expiresAt)
    }

    @Test
    fun `만료된 토큰은 파싱 시 예외를 던진다`() {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val expiredClaims = JwtClaims(
            userId = UserId.generate(),
            issuedAt = now.minusSeconds(7200),
            expiresAt = now.minusSeconds(3600),
        )

        val expiredToken = jwtProvider.issue(expiredClaims)

        assertThatThrownBy { jwtProvider.parse(expiredToken) }
            .isInstanceOf(InvalidJwtException::class.java)
    }

    @Test
    fun `서명이 다른 토큰은 파싱 시 예외를 던진다`() {
        val otherProvider: JwtProvider = JwtProviderImpl(
            JwtProperties(secret = "different-secret-key-for-jwt-at-least-32-bytes!!", expirationSeconds = 3600),
        )
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val token = otherProvider.issue(
            JwtClaims(userId = UserId.generate(), issuedAt = now, expiresAt = now.plusSeconds(3600)),
        )

        assertThatThrownBy { jwtProvider.parse(token) }
            .isInstanceOf(InvalidJwtException::class.java)
    }
}
