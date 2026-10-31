package com.moeum.conversation.support

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.util.*

class InMemoryMessageRepository : MessageRepository {
    val messages = linkedMapOf<MessageId, Message>()

    override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
        messages.values.find { it.userId == userId && it.clientMessageId == clientMessageId }

    override fun findPage(userId: UserId, recordDayId: RecordDayId, after: Long?, limit: Int): List<Message> {
        val byId = messages.values.filter { it.userId == userId && it.recordDayId == recordDayId }.sortedBy { it.streamSequence }
        val page = if (after != null) byId.filter { it.streamSequence > after }.take(limit) else byId.takeLast(limit)
        return page.sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
    }

    override fun findContext(userId: UserId, recordDayId: RecordDayId, throughSourceRevision: Long, limit: Int): List<Message> =
        messages.values.filter { it.userId==userId && it.recordDayId==recordDayId && it.sourceSequence<=throughSourceRevision }
            .sortedWith(compareBy({ it.sourceSequence }, { if (it.role == MessageRole.USER) 0 else 1 })).takeLast(limit)

    override fun answeredSourceRevision(userId: UserId, recordDayId: RecordDayId): Long =
        messages.values.filter { it.userId == userId && it.recordDayId == recordDayId && it.role == MessageRole.ASSISTANT }
            .maxOfOrNull { it.sourceSequence } ?: 0

    override fun findUnanswered(userId: UserId, recordDayId: RecordDayId, answered: Long, observed: Long, limit: Int): List<Message> =
        messages.values.filter { it.userId == userId && it.recordDayId == recordDayId && it.role == MessageRole.USER &&
            it.sourceSequence > answered && it.sourceSequence <= observed }.sortedBy { it.sourceSequence }.take(limit)

    override fun findJournalInput(userId: UserId, recordDayId: RecordDayId, sourceRevision: Long, closedAt: Instant): List<Message> {
        val lastUserStream = messages.values.single { it.userId == userId && it.recordDayId == recordDayId &&
            it.role == MessageRole.USER && it.sourceSequence == sourceRevision }.streamSequence
        return messages.values.filter { it.userId == userId && it.recordDayId == recordDayId &&
            it.sourceSequence <= sourceRevision && it.streamSequence <= lastUserStream &&
            (it.role == MessageRole.USER || it.occurredAt < closedAt) }.sortedBy { it.streamSequence }
    }
    override fun append(message: Message): Message {
        val saved = message.copy(streamSequence=(messages.values.filter { it.recordDayId==message.recordDayId }.maxOfOrNull { it.streamSequence } ?: 0)+1)
        messages[saved.id] = saved
        return saved
    }
}
