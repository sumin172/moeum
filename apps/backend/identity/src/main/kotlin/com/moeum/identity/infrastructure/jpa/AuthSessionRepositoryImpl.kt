package com.moeum.identity.infrastructure.jpa

import com.moeum.identity.domain.AuthSessionRepository
import com.moeum.identity.domain.model.AuthSession
import com.moeum.identity.domain.model.AuthSessionId
import com.moeum.kernel.UserId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.util.UUID

interface AuthSessionJpaRepository : JpaRepository<AuthSessionJpaEntity, UUID> {
    fun findByUserIdAndDeviceIdAndRevokedAtIsNull(userId: UUID, deviceId: UUID): AuthSessionJpaEntity?
}

@Component
class AuthSessionRepositoryImpl(
    private val jpaRepository: AuthSessionJpaRepository,
) : AuthSessionRepository {

    override fun findById(id: AuthSessionId): AuthSession? =
        jpaRepository.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findActiveByUserIdAndDeviceId(userId: UserId, deviceId: UUID): AuthSession? =
        jpaRepository.findByUserIdAndDeviceIdAndRevokedAtIsNull(userId.value, deviceId)?.toDomain()

    // 바로 flush한다 — 같은 트랜잭션에서 기존 세션 폐기(UPDATE) 뒤 새 세션(INSERT)을 저장할 때, Hibernate가
    // INSERT를 먼저 내보내면 "기기당 활성 세션 하나" 유니크 인덱스에 걸리기 때문이다.
    override fun save(session: AuthSession): AuthSession =
        jpaRepository.saveAndFlush(session.toEntity()).toDomain()
}

private fun AuthSessionJpaEntity.toDomain(): AuthSession =
    AuthSession(
        id = AuthSessionId(id),
        userId = UserId(userId),
        deviceId = deviceId,
        refreshTokenHash = refreshTokenHash,
        previousRefreshTokenHash = previousRefreshTokenHash,
        rotatedAt = rotatedAt,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        revokeReason = revokeReason,
        createdAt = createdAt,
        version = version,
    )

private fun AuthSession.toEntity(): AuthSessionJpaEntity =
    AuthSessionJpaEntity(
        id = id.value,
        userId = userId.value,
        deviceId = deviceId,
        refreshTokenHash = refreshTokenHash,
        previousRefreshTokenHash = previousRefreshTokenHash,
        rotatedAt = rotatedAt,
        expiresAt = expiresAt,
        revokedAt = revokedAt,
        revokeReason = revokeReason,
        createdAt = createdAt,
        version = version,
    )
