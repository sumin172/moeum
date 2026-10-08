package com.moeum.conversation.interfaces

import com.moeum.conversation.domain.AiQuotaExceededException
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.MessageNotFoundException
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.platform.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackages = ["com.moeum.conversation.interfaces"])
class ConversationExceptionHandler {

    private val log = LoggerFactory.getLogger(ConversationExceptionHandler::class.java)

    @ExceptionHandler(InvalidConversationRequestException::class)
    fun handleInvalidRequest(e: InvalidConversationRequestException): ResponseEntity<ErrorResponse> {
        log.warn("잘못된 conversation 요청: {}", e.message)
        return ResponseEntity.badRequest()
            .body(ErrorResponse(code = ConversationErrorCode.INVALID_REQUEST.code, message = ConversationErrorCode.INVALID_REQUEST.description))
    }

    @ExceptionHandler(MessageNotFoundException::class)
    fun handleMessageNotFound(e: MessageNotFoundException): ResponseEntity<ErrorResponse> {
        log.warn("응답을 요청할 수 없는 메시지: {}", e.message)
        return error(HttpStatus.NOT_FOUND, ConversationErrorCode.MESSAGE_NOT_FOUND)
    }

    @ExceptionHandler(ResponseAlreadyCompletedException::class)
    fun handleAlreadyCompleted(e: ResponseAlreadyCompletedException): ResponseEntity<ErrorResponse> {
        log.warn("이미 완료된 응답 재요청: {}", e.message)
        return error(HttpStatus.CONFLICT, ConversationErrorCode.RESPONSE_ALREADY_COMPLETED)
    }

    @ExceptionHandler(AiQuotaExceededException::class)
    fun handleQuotaExceeded(e: AiQuotaExceededException): ResponseEntity<ErrorResponse> {
        log.warn("AI 응답 quota 초과: {}", e.message)
        return error(HttpStatus.TOO_MANY_REQUESTS, ConversationErrorCode.QUOTA_EXCEEDED)
    }

    private fun error(status: HttpStatus, code: ConversationErrorCode): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(code = code.code, message = code.description))
}
