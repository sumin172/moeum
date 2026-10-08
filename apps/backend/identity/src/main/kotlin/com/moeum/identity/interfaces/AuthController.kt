package com.moeum.identity.interfaces

import com.moeum.identity.application.command.AuthSessionService
import com.moeum.identity.application.command.GoogleLoginService
import com.moeum.identity.application.command.RefreshOutcome
import com.moeum.identity.domain.InvalidRefreshTokenException
import com.moeum.identity.domain.RefreshTokenAlreadyRotatedException
import com.moeum.identity.domain.UserDeletedException
import com.moeum.identity.interfaces.dto.AuthTokenResponse
import com.moeum.identity.interfaces.dto.GoogleLoginRequest
import com.moeum.identity.interfaces.dto.RefreshTokenRequest
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val googleLoginService: GoogleLoginService,
    private val authSessionService: AuthSessionService,
) {

    @PostMapping("/google")
    fun loginWithGoogle(@RequestBody request: GoogleLoginRequest): AuthTokenResponse =
        AuthTokenResponse.from(googleLoginService.login(request.idToken, request.deviceId))

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshTokenRequest): AuthTokenResponse {
        val outcome = try {
            authSessionService.refresh(request.refreshToken)
        } catch (_: OptimisticLockingFailureException) {
            // 같은 세션을 동시에 refresh해서 다른 요청이 먼저 교체했다
            RefreshOutcome.AlreadyRotated
        }
        return when (outcome) {
            is RefreshOutcome.Refreshed -> AuthTokenResponse.from(outcome.tokens)
            RefreshOutcome.Invalid -> throw InvalidRefreshTokenException("유효하지 않은 refresh token입니다")
            RefreshOutcome.AlreadyRotated -> throw RefreshTokenAlreadyRotatedException("이미 교체된 refresh token입니다")
            RefreshOutcome.UserDeleted -> throw UserDeletedException("탈퇴한 사용자입니다")
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestBody request: RefreshTokenRequest) {
        authSessionService.logout(request.refreshToken)
    }
}
