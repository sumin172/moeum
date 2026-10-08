package com.moeum.journal.interfaces

import com.moeum.journal.domain.InvalidJournalRequestException
import com.moeum.journal.domain.JournalEditNotAllowedException
import com.moeum.journal.domain.JournalGenerationNotRetryableException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.JournalVersionConflictException
import com.moeum.platform.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackages = ["com.moeum.journal.interfaces"])
class JournalExceptionHandler {

    private val log = LoggerFactory.getLogger(JournalExceptionHandler::class.java)

    @ExceptionHandler(InvalidJournalRequestException::class)
    fun handleInvalid(e: InvalidJournalRequestException) = error(HttpStatus.BAD_REQUEST, JournalErrorCode.INVALID_REQUEST, e)

    @ExceptionHandler(JournalNotFoundException::class)
    fun handleNotFound(e: JournalNotFoundException) = error(HttpStatus.NOT_FOUND, JournalErrorCode.NOT_FOUND, e)

    // 요청의 version이 낡았거나(JournalVersionConflictException), 같은 version으로 동시에 저장했다(@Version)
    @ExceptionHandler(JournalVersionConflictException::class, OptimisticLockingFailureException::class)
    fun handleConflict(e: Exception) = error(HttpStatus.CONFLICT, JournalErrorCode.VERSION_CONFLICT, e)

    @ExceptionHandler(JournalEditNotAllowedException::class)
    fun handleNotAllowed(e: JournalEditNotAllowedException) = error(HttpStatus.FORBIDDEN, JournalErrorCode.EDIT_NOT_ALLOWED, e)

    @ExceptionHandler(JournalGenerationNotRetryableException::class)
    fun handleNotRetryable(e: JournalGenerationNotRetryableException) =
        error(HttpStatus.CONFLICT, JournalErrorCode.GENERATION_NOT_RETRYABLE, e)

    private fun error(status: HttpStatus, code: JournalErrorCode, e: Exception): ResponseEntity<ErrorResponse> {
        log.info("일기 요청 거부: code={}, reason={}", code.code, e.message)
        return ResponseEntity.status(status).body(ErrorResponse(code = code.code, message = code.description))
    }
}
