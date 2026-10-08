package com.moeum.conversation.support

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class InMemoryMessageRepository : MessageRepository {
    val messages = linkedMapOf<MessageId, Message>()

    override fun findById(id: MessageId): Message? = messages[id]

    override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
        messages.values.find { it.userId == userId && it.clientMessageId == clientMessageId }

    override fun findPage(userId: UserId, dayDate: LocalDate, after: MessageId?, limit: Int): List<Message> {
        val byId = messages.values.filter { it.userId == userId && it.dayDate == dayDate }.sortedBy { it.id.value }
        val page = if (after != null) byId.filter { it.id.value > after.value }.take(limit) else byId.takeLast(limit)
        return page.sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
    }

    override fun findAllByUserIdAndDayDate(userId: UserId, dayDate: LocalDate): List<Message> =
        messages.values.filter { it.userId == userId && it.dayDate == dayDate }
            .sortedWith(compareBy({ it.occurredAt }, { it.id.value }))

    override fun findCreatedBetween(from: Instant, to: Instant): List<Message> =
        messages.values.filter { it.createdAt >= from && it.createdAt < to }

    override fun save(message: Message): Message {
        messages[message.id] = message
        return message
    }
}
