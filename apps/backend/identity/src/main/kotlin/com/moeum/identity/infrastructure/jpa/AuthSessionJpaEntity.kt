package com.moeum.identity.infrastructure.jpa

import jakarta.persistence.*
import java.time.Instant
import java.util.*

@Entity
@Table(name = "auth_sessions", schema = "identity")
class AuthSessionJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "device_id", nullable = false)
    val deviceId: UUID,
    @Column(name = "refresh_token_hash", nullable = false)
    val refreshTokenHash: String,
    @Column(name = "previous_refresh_token_hash")
    val previousRefreshTokenHash: String?,
    @Column(name = "rotated_at")
    val rotatedAt: Instant?,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "revoked_at")
    val revokedAt: Instant?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Version
    @Column(nullable = false)
    val version: Long,
)
