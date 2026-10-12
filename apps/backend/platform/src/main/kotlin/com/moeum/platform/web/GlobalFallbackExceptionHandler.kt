package com.moeum.platform.web

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import com.moeum.platform.observability.SafeFailure
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

// 업무 모듈의 예외 처리기가 다루지 않은 예외의 마지막 처리기. 요청 형식 오류는 400, 없는 경로는 404, 나머지는 500.
@ConditionalOnRole(ExecutionRole.API)
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
        log.info("잘못된 요청 형식: {}", SafeFailure.code(e))
        return ResponseEntity.badRequest()
            .body(ErrorResponse(code = PlatformErrorCode.INVALID_REQUEST.code, message = PlatformErrorCode.INVALID_REQUEST.description))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        // 삭제되거나 없는 경로의 Spring 404를 일반 서버 오류로 바꾸지 않는다.
        if (e is org.springframework.web.ErrorResponse && e.statusCode.value() == 404) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(code="NOT_FOUND",message="요청한 경로가 없습니다"))
        }
        log.error("처리되지 않은 예외: code={}, location={}", SafeFailure.code(e), SafeFailure.location(e))
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(code = PlatformErrorCode.INTERNAL_ERROR.code, message = PlatformErrorCode.INTERNAL_ERROR.description))
    }
}
