package com.moeum.identity.application.command

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.UserDeletedException
import com.moeum.identity.domain.UserRepository
import com.moeum.identity.domain.model.User
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class GoogleLoginService(
    private val googleIdTokenVerifier: GoogleIdTokenVerifierPort,
    private val userRepository: UserRepository,
    private val authSessionService: AuthSessionService,
    private val timeProvider: TimeProvider,
) {
    // Google 검증(외부 호출)은 트랜잭션 밖에서 하고, 세션 생성만 트랜잭션으로 묶는다.
    fun login(idToken: String, deviceId: UUID): AuthTokens {
        val profile = googleIdTokenVerifier.verify(idToken)

        val user = userRepository.findByGoogleId(profile.googleId)
            ?: userRepository.save(
                User.create(
                    id = UserId.generate(),
                    googleId = profile.googleId,
                    email = profile.email,
                    now = timeProvider.now(),
                ),
            )
        if (user.isDeleted) {
            throw UserDeletedException("탈퇴한 사용자입니다: userId=${user.id.value}")
        }

        return authSessionService.start(user.id, deviceId)
    }
}
