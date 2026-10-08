package com.moeum.platform.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

// 업무 모듈의 예외 처리기가 다루지 않은 예외의 마지막 처리기. 클라이언트 요청 형식 오류는 400, 나머지는 500.
@RestControllerAdvice
class GlobalFallbackExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalFallbackExceptionHandler::class.java)

    // body 파싱 실패(필수 필드 누락·JSON 형식·타입 오류), 쿼리 파라미터 누락·타입 불일치
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun handleBadRequest(e: Exception): ResponseEntity<ErrorResponse> {
        log.info("잘못된 요청 형식: {}", e.message)
        return ResponseEntity.badRequest()
            .body(ErrorResponse(code = PlatformErrorCode.INVALID_REQUEST.code, message = PlatformErrorCode.INVALID_REQUEST.description))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("처리되지 않은 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = PlatformErrorCode.INTERNAL_ERROR.code, message = PlatformErrorCode.INTERNAL_ERROR.description))
    }
}
