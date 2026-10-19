package com.moeum.journal.interfaces

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import com.moeum.journal.domain.InvalidJournalRequestException
import com.moeum.journal.domain.JournalEditNotAllowedException
import com.moeum.journal.domain.JournalGenerationNotRetryableException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.JournalVersionConflictException
import com.moeum.platform.web.ErrorResponse
import com.moeum.platform.observability.SafeFailure
import com.moeum.platform.job.ManualRetryUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@ConditionalOnRole(ExecutionRole.API)
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

    @ExceptionHandler(ManualRetryUnavailableException::class)
    fun retryUnavailable(e: ManualRetryUnavailableException): ResponseEntity<ErrorResponse> =
        if (e.nextAllowedAt == null) ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse(code="JOURNAL_RETRY_EXHAUSTED",message="일기 생성 재시도 횟수를 모두 사용했습니다"))
        else ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorResponse(code="JOURNAL_RETRY_TOO_SOON",message="잠시 후 다시 요청해주세요"))

    private fun error(status: HttpStatus, code: JournalErrorCode, e: Exception): ResponseEntity<ErrorResponse> {
        log.info("일기 요청 거부: code={}, reason={}", code.code, SafeFailure.code(e))
        return ResponseEntity.status(status).body(ErrorResponse(code = code.code, message = code.description))
    }
}
