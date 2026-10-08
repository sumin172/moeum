package com.moeum.identity.domain

import com.moeum.identity.domain.model.AuthSession
import com.moeum.identity.domain.model.AuthSessionId
import com.moeum.kernel.UserId
import java.util.UUID

interface AuthSessionRepository {
    fun findById(id: AuthSessionId): AuthSession?
    fun findActiveByUserIdAndDeviceId(userId: UserId, deviceId: UUID): AuthSession?
    // 낙관적 락(version) — 같은 세션을 동시에 refresh하면 하나만 성공한다
    fun save(session: AuthSession): AuthSession
}
