package com.moeum.conversation.interfaces

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import com.moeum.conversation.domain.AiQuotaExceededException
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.RecordDayNotFoundException
import com.moeum.platform.web.ErrorResponse
import com.moeum.platform.observability.SafeFailure
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@ConditionalOnRole(ExecutionRole.API)
@RestControllerAdvice(basePackages = ["com.moeum.conversation.interfaces"])
class ConversationExceptionHandler {

    private val log = LoggerFactory.getLogger(ConversationExceptionHandler::class.java)

    @ExceptionHandler(InvalidConversationRequestException::class)
    fun handleInvalidRequest(e: InvalidConversationRequestException): ResponseEntity<ErrorResponse> {
        log.warn("잘못된 conversation 요청: {}", SafeFailure.code(e))
        return ResponseEntity.badRequest()
            .body(ErrorResponse(code = ConversationErrorCode.INVALID_REQUEST.code, message = ConversationErrorCode.INVALID_REQUEST.description))
    }

    @ExceptionHandler(AiQuotaExceededException::class)
    fun handleQuotaExceeded(e: AiQuotaExceededException): ResponseEntity<QuotaErrorResponse> {
        log.warn("AI 응답 quota 초과: {}", SafeFailure.code(e))
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(QuotaErrorResponse("QUOTA_EXCEEDED", "대화 한도를 초과했습니다", e.quota))
    }

    @ExceptionHandler(RecordDayNotFoundException::class)
    fun handleRecordMissing(): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(code = "RECORD_NOT_FOUND", message = "기록이 없습니다"))
    @ExceptionHandler(com.moeum.conversation.domain.ConversationRateExceededException::class)
    fun rateExceeded(): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErrorResponse(code="MESSAGE_RATE_EXCEEDED",message="잠시 후 다시 전송해주세요"))
}

data class QuotaErrorResponse(val code: String, val message: String, val quota: com.moeum.conversation.domain.ConversationQuota)
