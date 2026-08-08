package com.moeum.conversation.interfaces

import com.moeum.conversation.domain.InvalidConversationRequestException
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

    @ExceptionHandler(ConversationDayConflictException::class)
    fun handleConflict(e: ConversationDayConflictException): ResponseEntity<ErrorResponse> {
        log.warn("ConversationDay 충돌 재시도 소진: {}", e.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse(code = ConversationErrorCode.DAY_CONFLICT.code, message = ConversationErrorCode.DAY_CONFLICT.description))
    }
}
