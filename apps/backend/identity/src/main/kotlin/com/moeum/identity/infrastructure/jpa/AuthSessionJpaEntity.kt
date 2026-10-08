package com.moeum.identity.infrastructure.jpa

import com.moeum.identity.domain.model.AuthSessionRevokeReason
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

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
    @Column(name = "revoke_reason")
    @Enumerated(EnumType.STRING)
    val revokeReason: AuthSessionRevokeReason?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Version
    @Column(nullable = false)
    val version: Long,
)
