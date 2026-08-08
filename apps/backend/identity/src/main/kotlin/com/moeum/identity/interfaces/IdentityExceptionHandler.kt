package com.moeum.identity.interfaces

import com.moeum.identity.domain.InvalidGoogleTokenException
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
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(code = IdentityErrorCode.INVALID_GOOGLE_TOKEN.code, message = IdentityErrorCode.INVALID_GOOGLE_TOKEN.description))
    }
}
