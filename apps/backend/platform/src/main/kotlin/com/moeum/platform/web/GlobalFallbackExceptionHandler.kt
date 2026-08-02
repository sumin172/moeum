package com.moeum.platform.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalFallbackExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalFallbackExceptionHandler::class.java)

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = PlatformErrorCode.INTERNAL_ERROR.code, message = PlatformErrorCode.INTERNAL_ERROR.description))
    }
}
