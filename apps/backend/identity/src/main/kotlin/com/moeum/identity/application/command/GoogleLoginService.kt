package com.moeum.identity.application.command

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.model.User
import com.moeum.identity.domain.UserRepository
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.security.jwt.JwtProvider
import org.springframework.stereotype.Service

@Service
class GoogleLoginService(
    private val googleIdTokenVerifier: GoogleIdTokenVerifierPort,
    private val userRepository: UserRepository,
    private val jwtProvider: JwtProvider,
    private val timeProvider: TimeProvider,
) {
    fun login(idToken: String): String {
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

        return jwtProvider.issue(user.id)
    }
}
