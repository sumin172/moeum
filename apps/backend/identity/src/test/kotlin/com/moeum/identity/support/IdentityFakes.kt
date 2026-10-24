package com.moeum.identity.support

import com.moeum.identity.domain.AuthSessionRepository
import com.moeum.identity.domain.UserRepository
import com.moeum.identity.domain.model.AuthSession
import com.moeum.identity.domain.model.AuthSessionId
import com.moeum.identity.domain.model.User
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.security.jwt.IssuedAccessToken
import com.moeum.platform.security.jwt.JwtClaims
import com.moeum.platform.security.jwt.JwtProvider
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Duration
import java.time.Instant
import java.util.UUID

class InMemoryUserRepository : UserRepository {
    val users = linkedMapOf<UserId, User>()
    override fun findById(id: UserId): User? = users[id]
    override fun findByGoogleId(googleId: String): User? = users.values.find { it.googleId == googleId }
    override fun save(user: User): User = user.also { users[it.id] = it }
}

// 낙관적 락(version)과 "기기당 활성 세션 하나" 제약을 흉내 낸다
class InMemoryAuthSessionRepository : AuthSessionRepository {
    val sessions = linkedMapOf<AuthSessionId, AuthSession>()

    override fun findById(id: AuthSessionId): AuthSession? = sessions[id]

    override fun findActiveByUserIdAndDeviceId(userId: UserId, deviceId: UUID): AuthSession? =
        sessions.values.find { it.userId == userId && it.deviceId == deviceId && it.revokedAt == null }

    override fun save(session: AuthSession): AuthSession {
        val current = sessions[session.id]
        if (current != null && current.version != session.version) {
            throw ObjectOptimisticLockingFailureException(AuthSession::class.java, session.id.value)
        }
        check(session.revokedAt != null || sessions.values.none {
            it.id != session.id && it.userId == session.userId && it.deviceId == session.deviceId && it.revokedAt == null
        }) { "uq_identity_auth_sessions_active_device" }
        val saved = session.copy(version = if (current == null) session.version else session.version + 1)
        sessions[session.id] = saved
        return saved
    }
}

class FakeJwtProvider(private val timeProvider: TimeProvider) : JwtProvider {
    override fun issue(userId: UserId): IssuedAccessToken =
        IssuedAccessToken("access-${userId.value}-${timeProvider.now()}", timeProvider.now().plus(Duration.ofMinutes(15)))
    override fun parse(token: String): JwtClaims = throw UnsupportedOperationException("not used in this test")
}

class MutableTimeProvider(var now: Instant) : TimeProvider {
    override fun now(): Instant = now
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
