package com.moeum.identity.application.command

import com.moeum.identity.domain.AuthSessionRepository
import com.moeum.identity.domain.UserRepository
import com.moeum.identity.domain.model.AuthSession
import com.moeum.identity.domain.model.AuthSessionId
import com.moeum.identity.domain.model.AuthSessionRevokeReason
import com.moeum.identity.domain.model.RefreshToken
import com.moeum.identity.infrastructure.config.AuthSessionProperties
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.security.jwt.JwtProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

// refresh 결과. 거부도 예외가 아니라 값으로 돌려준다 — 재사용 감지로 세션을 폐기한 것은 커밋돼야 하는데,
// 트랜잭션 안에서 예외를 던지면 그 폐기까지 롤백되기 때문이다. 에러 응답 변환은 호출부(컨트롤러)가 한다.
sealed interface RefreshOutcome {
    data class Refreshed(val tokens: AuthTokens) : RefreshOutcome
    data object Invalid : RefreshOutcome
    data object AlreadyRotated : RefreshOutcome
    data object UserDeleted : RefreshOutcome
}

@Service
class AuthSessionService(
    private val authSessionRepository: AuthSessionRepository,
    private val userRepository: UserRepository,
    private val jwtProvider: JwtProvider,
    private val properties: AuthSessionProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(AuthSessionService::class.java)

    // 로그인: 같은 기기의 이전 세션은 폐기하고 새 세션을 연다(기기당 활성 세션 하나).
    @Transactional
    fun start(userId: UserId, deviceId: UUID): AuthTokens {
        val now = timeProvider.now()
        authSessionRepository.findActiveByUserIdAndDeviceId(userId, deviceId)?.let {
            authSessionRepository.save(it.revoked(AuthSessionRevokeReason.RELOGIN_ON_SAME_DEVICE, now))
        }
        val sessionId = AuthSessionId.generate()
        val refreshToken = RefreshToken.issue(sessionId)
        val session = authSessionRepository.save(
            AuthSession.start(sessionId, userId, deviceId, refreshToken.hash(), now, properties.refreshTokenTtl),
        )
        return issueTokens(userId, refreshToken, session)
    }

    @Transactional
    fun refresh(refreshTokenValue: String): RefreshOutcome {
        val now = timeProvider.now()
        val token = RefreshToken.parse(refreshTokenValue) ?: return RefreshOutcome.Invalid
        val session = authSessionRepository.findById(token.sessionId) ?: return RefreshOutcome.Invalid
        if (!session.isActive(now)) return RefreshOutcome.Invalid

        val presentedHash = token.hash()
        if (!session.matchesCurrent(presentedHash)) {
            if (session.isRecentlyRotated(presentedHash, now, properties.rotationGrace)) {
                return RefreshOutcome.AlreadyRotated
            }
            // 이미 교체된 예전 토큰이 다시 쓰였다 — 토큰이 탈취됐을 수 있으므로 세션 전체를 끊는다.
            log.warn("refresh token 재사용 감지, 세션 폐기: sessionId={}, userId={}", session.id.value, session.userId.value)
            authSessionRepository.save(session.revoked(AuthSessionRevokeReason.REFRESH_TOKEN_REUSED, now))
            return RefreshOutcome.Invalid
        }

        val user = userRepository.findById(session.userId)
        if (user == null || user.isDeleted) {
            authSessionRepository.save(session.revoked(AuthSessionRevokeReason.USER_DELETED, now))
            return RefreshOutcome.UserDeleted
        }

        val newToken = RefreshToken.issue(session.id)
        val rotated = authSessionRepository.save(session.rotated(newToken.hash(), now, properties.refreshTokenTtl))
        return RefreshOutcome.Refreshed(issueTokens(user.id, newToken, rotated))
    }

    // 로그아웃은 멱등이다 — 이미 끝난 세션이나 알 수 없는 토큰이어도 성공으로 본다.
    @Transactional
    fun logout(refreshTokenValue: String) {
        val token = RefreshToken.parse(refreshTokenValue) ?: return
        val session = authSessionRepository.findById(token.sessionId) ?: return
        val hash = token.hash()
        val now = timeProvider.now()
        val reason = if (session.matchesCurrent(hash) || session.isRecentlyRotated(hash, now, properties.rotationGrace)) {
            AuthSessionRevokeReason.LOGOUT
        } else {
            AuthSessionRevokeReason.REFRESH_TOKEN_REUSED
        }
        authSessionRepository.save(session.revoked(reason, now))
    }

    private fun issueTokens(userId: UserId, refreshToken: RefreshToken, session: AuthSession): AuthTokens {
        val access = jwtProvider.issue(userId)
        return AuthTokens(
            accessToken = access.token,
            accessTokenExpiresAt = access.expiresAt,
            refreshToken = refreshToken.value,
            refreshTokenExpiresAt = session.expiresAt,
        )
    }
}
