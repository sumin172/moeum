package com.moeum.identity.domain.model

import com.moeum.kernel.UserId
import com.moeum.kernel.UuidV7
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AuthSessionId(val value: UUID) {
    companion object {
        fun generate(): AuthSessionId = AuthSessionId(UuidV7.generate())
    }
}

// 기기 하나의 로그인 세션. 현재·직전 비밀값의 해시로 갱신과 동시 요청 유예를 판단한다.
// 토큰의 발급 진위는 HMAC 검증이 담당하며, 검증된 과거 토큰의 재사용은 세션을 폐기한다.
data class AuthSession(
    val id: AuthSessionId,
    val userId: UserId,
    // 클라이언트가 앱 설치 단위로 만든 식별자. 같은 기기로 로그인할 때 활성 세션을 교체하는 기준.
    val deviceId: UUID,
    val refreshTokenHash: String,
    // 직전 토큰 해시 — 동시 refresh(같은 토큰으로 거의 동시에 두 번 요청)를 재사용으로 오인하지 않기 위함
    val previousRefreshTokenHash: String? = null,
    val rotatedAt: Instant? = null,
    // 마지막 발급(로그인·refresh) 기준으로 연장된다
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val createdAt: Instant,
    val version: Long = 0,
) {
    fun isActive(now: Instant): Boolean = revokedAt == null && now.isBefore(expiresAt)

    fun matchesCurrent(tokenHash: String): Boolean = tokenHash == refreshTokenHash

    // 방금 교체된 직전 토큰이 유예 시간 안에 다시 들어온 경우 — 클라이언트의 동시 요청으로 보고 세션을 유지한다.
    fun isRecentlyRotated(tokenHash: String, now: Instant, grace: Duration): Boolean =
        tokenHash == previousRefreshTokenHash && rotatedAt != null && now.isBefore(rotatedAt.plus(grace))

    fun rotated(newTokenHash: String, now: Instant, ttl: Duration): AuthSession =
        copy(
            refreshTokenHash = newTokenHash,
            previousRefreshTokenHash = refreshTokenHash,
            rotatedAt = now,
            expiresAt = now.plus(ttl),
        )

    fun revoked(now: Instant): AuthSession =
        if (revokedAt != null) this else copy(revokedAt = now)

    companion object {
        fun start(id: AuthSessionId, userId: UserId, deviceId: UUID, tokenHash: String, now: Instant, ttl: Duration): AuthSession =
            AuthSession(
                id = id,
                userId = userId,
                deviceId = deviceId,
                refreshTokenHash = tokenHash,
                expiresAt = now.plus(ttl),
                createdAt = now,
            )
    }
}
