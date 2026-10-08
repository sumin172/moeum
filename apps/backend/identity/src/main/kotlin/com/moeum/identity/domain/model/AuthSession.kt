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

enum class AuthSessionRevokeReason { LOGOUT, RELOGIN_ON_SAME_DEVICE, REFRESH_TOKEN_REUSED, USER_DELETED }

// 기기 하나의 로그인 세션. refresh token은 "<세션 ID>.<비밀값>"이고, 여기에는 현재 비밀값의 해시만 둔다.
// refresh할 때마다 비밀값을 새로 발급(rotation)하므로, 이 세션의 예전 토큰이 다시 들어오면 해시가 맞지 않는다 —
// 토큰 이력 없이도 탈취·재사용을 감지해 세션 전체를 폐기할 수 있다.
data class AuthSession(
    val id: AuthSessionId,
    val userId: UserId,
    // 클라이언트가 앱 설치 단위로 만든 식별자. 기기별 세션 관리("다른 기기에서 로그아웃")의 기준.
    val deviceId: UUID,
    val refreshTokenHash: String,
    // 직전 토큰 해시 — 동시 refresh(같은 토큰으로 거의 동시에 두 번 요청)를 재사용으로 오인하지 않기 위함
    val previousRefreshTokenHash: String? = null,
    val rotatedAt: Instant? = null,
    // 마지막 발급(로그인·refresh) 기준으로 연장된다
    val expiresAt: Instant,
    val revokedAt: Instant? = null,
    val revokeReason: AuthSessionRevokeReason? = null,
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

    fun revoked(reason: AuthSessionRevokeReason, now: Instant): AuthSession =
        if (revokedAt != null) this else copy(revokedAt = now, revokeReason = reason)

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
