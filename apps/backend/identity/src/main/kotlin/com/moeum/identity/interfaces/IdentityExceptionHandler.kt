package com.moeum.identity.interfaces

import com.moeum.identity.domain.InvalidGoogleTokenException
import com.moeum.identity.domain.InvalidRefreshTokenException
import com.moeum.identity.domain.RefreshTokenAlreadyRotatedException
import com.moeum.identity.domain.UserDeletedException
import com.moeum.platform.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackages = ["com.moeum.identity.interfaces"])
class IdentityExceptionHandler {

    private val log = LoggerFactory.getLogger(IdentityExceptionHandler::class.java)

    @ExceptionHandler(InvalidGoogleTokenException::class)
    fun handleInvalidGoogleToken(e: InvalidGoogleTokenException): ResponseEntity<ErrorResponse> {
        log.warn("Google 로그인 실패: {}", e.message)
        return error(HttpStatus.UNAUTHORIZED, IdentityErrorCode.INVALID_GOOGLE_TOKEN)
    }

    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(e: InvalidRefreshTokenException): ResponseEntity<ErrorResponse> {
        log.info("refresh 거부: {}", e.message)
        return error(HttpStatus.UNAUTHORIZED, IdentityErrorCode.INVALID_REFRESH_TOKEN)
    }

    @ExceptionHandler(RefreshTokenAlreadyRotatedException::class)
    fun handleAlreadyRotated(e: RefreshTokenAlreadyRotatedException): ResponseEntity<ErrorResponse> {
        log.info("동시 refresh: {}", e.message)
        return error(HttpStatus.CONFLICT, IdentityErrorCode.REFRESH_TOKEN_ROTATED)
    }

    @ExceptionHandler(UserDeletedException::class)
    fun handleUserDeleted(e: UserDeletedException): ResponseEntity<ErrorResponse> {
        log.warn("탈퇴 사용자 인증 시도: {}", e.message)
        return error(HttpStatus.FORBIDDEN, IdentityErrorCode.USER_DELETED)
    }

    private fun error(status: HttpStatus, code: IdentityErrorCode): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(code = code.code, message = code.description))
}
