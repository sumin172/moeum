package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.parseTimezone
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
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

@Service
class SaveMessageService(
    private val conversationDayRepository: ConversationDayRepository,
    private val messageRepository: MessageRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(SaveMessageService::class.java)

    @Transactional
    fun save(userId: UserId, command: SaveMessageCommand): Message {
        if (command.content.isBlank()) {
            throw InvalidConversationRequestException("메시지 내용은 비어있을 수 없습니다")
        }
        val zoneId = parseTimezone(command.timezone)

        messageRepository.findByUserIdAndClientMessageId(userId, command.clientMessageId)?.let { return it }

        val localDate = command.occurredAt.atZone(zoneId).toLocalDate()
        val conversationDay = conversationDayRepository.findByUserIdAndLocalDate(userId, localDate)
            ?: newConversationDay(userId, localDate, command.timezone)

        conversationDayRepository.save(conversationDay.withMessageAdded(command.timezone))

        val message = Message.userMessage(
            id = MessageId.generate(),
            conversationDayId = conversationDay.id,
            userId = userId,
            content = command.content,
            occurredAt = command.occurredAt,
            timezone = command.timezone,
            localDate = localDate,
            clientMessageId = command.clientMessageId,
        )
        return messageRepository.save(message)
    }

    private fun newConversationDay(userId: UserId, localDate: java.time.LocalDate, timezone: String): ConversationDay {
        log.info("새 ConversationDay 생성: userId={}, localDate={}", userId.value, localDate)
        return ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = userId,
            localDate = localDate,
            timezone = timezone,
            now = timeProvider.now(),
        )
    }
}
