package com.moeum.conversation.application.command

import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.findOrDefault
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.parseTimezone
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class SaveMessageCommand(
    val clientMessageId: UUID,
    val content: String,
    val occurredAt: Instant,
    val timezone: String,
)

data class SaveMessageResult(val message: Message, val isNewlyCreated: Boolean)

@Service
class SaveMessageService(
    private val dayPreferenceRepository: DayPreferenceRepository,
    private val messageRepository: MessageRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun save(userId: UserId, command: SaveMessageCommand): SaveMessageResult {
        if (command.content.isBlank()) {
            throw InvalidConversationRequestException("메시지 내용은 비어있을 수 없습니다")
        }
        val zoneId = parseTimezone(command.timezone)

        messageRepository.findByUserIdAndClientMessageId(userId, command.clientMessageId)?.let {
            return SaveMessageResult(it, isNewlyCreated = false)
        }

        val now = timeProvider.now()
        // dayDate는 발화 시각(occurredAt) 기준이다 — 오프라인으로 늦게 도착한 메시지도 실제 발화한 하루에 들어간다.
        val dayDate = dayPreferenceRepository.findOrDefault(userId, now).dayDateOf(command.occurredAt, zoneId)
        val message = Message.userMessage(
            id = MessageId.generate(),
            userId = userId,
            content = command.content,
            occurredAt = command.occurredAt,
            timezone = command.timezone,
            localDate = command.occurredAt.atZone(zoneId).toLocalDate(),
            dayDate = dayDate,
            clientMessageId = command.clientMessageId,
            now = now,
        )
        return SaveMessageResult(messageRepository.save(message), isNewlyCreated = true)
    }
}
