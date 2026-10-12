package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.util.UUID

interface MessageRepository {
    fun findByUserIdAndId(userId: UserId, id: MessageId): Message?
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(userId: UserId, recordDayId: RecordDayId, after: Long?, limit: Int): List<Message>
    fun findContext(userId: UserId, recordDayId: RecordDayId, inputSourceRevision: Long, limit: Int): List<Message>
    fun findSource(userId: UserId, recordDayId: RecordDayId, sourceRevision: Long): List<Message>
    fun append(message: Message): Message
}
