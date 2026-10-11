package com.moeum.conversation.support

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.util.*

class InMemoryMessageRepository : MessageRepository {
    val messages = linkedMapOf<MessageId, Message>()

    override fun findByUserIdAndId(userId: UserId, id: MessageId): Message? = messages[id]?.takeIf { it.userId == userId }

    override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
        messages.values.find { it.userId == userId && it.clientMessageId == clientMessageId }

    override fun findPage(userId: UserId, recordDayId: RecordDayId, after: MessageId?, limit: Int): List<Message> {
        val byId = messages.values.filter { it.userId == userId && it.recordDayId == recordDayId }.sortedBy { it.id.value }
        val page = if (after != null) byId.filter { it.id.value > after.value }.take(limit) else byId.takeLast(limit)
        return page.sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
    }

    override fun findAllByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): List<Message> =
        messages.values.filter { it.userId == userId && it.recordDayId == recordDayId }
            .sortedWith(compareBy({ it.occurredAt }, { it.id.value }))

    override fun findSource(userId: UserId, recordDayId: RecordDayId, sourceRevision: Long): List<Message> =
        messages.values.filter { it.userId == userId && it.recordDayId == recordDayId && it.role == com.moeum.conversation.domain.model.MessageRole.USER && it.sourceSequence!! <= sourceRevision }
            .sortedWith(compareBy({ it.occurredAt }, { it.sourceSequence }))
    override fun append(message: Message): Message {
        messages[message.id] = message
        return message
    }
}
