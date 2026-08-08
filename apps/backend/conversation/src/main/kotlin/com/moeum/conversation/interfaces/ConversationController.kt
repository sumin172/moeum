package com.moeum.conversation.interfaces

import com.moeum.conversation.application.command.GenerateConversationResponseService
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
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

class ConversationDayConflictException(message: String) : RuntimeException(message)

private const val MAX_SAVE_ATTEMPTS = 3
private const val DEFAULT_PAGE_SIZE = "50"
private const val MAX_PAGE_SIZE = 200

@RestController
@RequestMapping("/api/conversations")
class ConversationController(
    private val saveMessageService: SaveMessageService,
    private val getTodayConversationService: GetTodayConversationService,
    private val generateConversationResponseService: GenerateConversationResponseService,
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

        repeat(MAX_SAVE_ATTEMPTS) { attempt ->
            try {
                val result = saveMessageService.save(userId, command)
                // 이번 호출이 실제로 새 행을 커밋했을 때만 트리거
                if (result.isNewlyCreated) {
                    generateConversationResponseService.generateAsync(result.message)
                }
                return MessageResponse.from(result.message)
            } catch (_: ObjectOptimisticLockingFailureException) {
                log.warn("ConversationDay 낙관적 락 충돌, 재시도: userId={}, attempt={}", userId.value, attempt + 1)
            } catch (_: DataIntegrityViolationException) {
                log.warn("메시지 저장 유니크 제약 경합, 재시도: userId={}, attempt={}", userId.value, attempt + 1)
            }
        }
        throw ConversationDayConflictException(
            "메시지 저장이 여러 번 충돌했습니다: userId=${userId.value}, attempts=$MAX_SAVE_ATTEMPTS",
        )
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
