package com.moeum.conversation.interfaces

import com.moeum.conversation.application.command.ResponseJobExecutor
import com.moeum.conversation.application.command.RetryResponseService
import com.moeum.conversation.application.command.SaveMessageCommand
import com.moeum.conversation.application.command.SaveMessageService
import com.moeum.conversation.application.query.GetTodayConversationService
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.interfaces.dto.MessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.conversation.interfaces.dto.TodayConversationResponse
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private const val DEFAULT_PAGE_SIZE = "50"
private const val MAX_PAGE_SIZE = 200

@RestController
@RequestMapping("/api/conversations")
class ConversationController(
    private val saveMessageService: SaveMessageService,
    private val getTodayConversationService: GetTodayConversationService,
    private val retryResponseService: RetryResponseService,
    private val responseJobExecutor: ResponseJobExecutor,
) {
    private val log = LoggerFactory.getLogger(ConversationController::class.java)

    @PostMapping("/messages")
    fun saveMessage(@AuthenticationPrincipal userId: UserId, @RequestBody request: SaveMessageRequest): MessageResponse {
        val command = SaveMessageCommand(
            clientMessageId = request.clientMessageId,
            content = request.content,
            occurredAt = request.occurredAt,
            timezone = request.timezone,
        )

        val result = try {
            saveMessageService.save(userId, command)
        } catch (_: DataIntegrityViolationException) {
            // 같은 clientMessageId가 동시에 두 번 들어온 경합 — 다시 호출하면 먼저 커밋된 메시지를 돌려받는다.
            log.warn("메시지 저장 유니크 제약 경합, 재조회: userId={}, clientMessageId={}", userId.value, command.clientMessageId)
            saveMessageService.save(userId, command)
        }
        // 이번 호출이 실제로 새 행을 커밋했을 때만 바로 실행을 시도한다. 여기서 유실돼도 poller가 회수한다.
        if (result.isNewlyCreated) {
            responseJobExecutor.executeAsync(result.responseJob.id)
        }
        return MessageResponse.from(result.message, result.responseJob)
    }

    // 실패한 AI 응답을 다시 요청한다. 대기·처리 중이면 현재 상태를 그대로 돌려준다(중복 요청 무시).
    @PostMapping("/messages/{messageId}/response-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryResponse(@AuthenticationPrincipal userId: UserId, @PathVariable messageId: UUID): MessageResponse {
        val result = retryResponseService.retry(userId, MessageId(messageId))
        if (result.restarted) {
            responseJobExecutor.executeAsync(result.responseJob.id)
        }
        return MessageResponse.from(result.message, result.responseJob)
    }

    @GetMapping("/today")
    fun getToday(
        @AuthenticationPrincipal userId: UserId,
        @RequestParam timezone: String,
        @RequestParam(required = false, defaultValue = "false") previousDay: Boolean,
        @RequestParam(required = false) after: UUID?,
        @RequestParam(required = false, defaultValue = DEFAULT_PAGE_SIZE) limit: Int,
    ): TodayConversationResponse {
        val cursor = after?.let { MessageId(it) }
        val boundedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        return TodayConversationResponse.from(
            getTodayConversationService.getToday(userId, timezone, previousDay, cursor, boundedLimit),
        )
    }
}
