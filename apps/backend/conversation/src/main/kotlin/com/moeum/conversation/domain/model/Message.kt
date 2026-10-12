package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.util.UUID

enum class MessageRole { USER, ASSISTANT }

data class Message(
    val id: MessageId,
    val userId: UserId,
    val role: MessageRole,
    val content: String,
    val occurredAt: Instant,
    val timezone: String,
    val recordDayId: RecordDayId,
    val sourceSequence: Long,
    val clientMessageId: UUID?,
    val generationId: UUID? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val createdAt: Instant,
    val streamSequence: Long = 0,
) {
    companion object {
        fun userMessage(
            id: MessageId, userId: UserId, content: String, occurredAt: Instant, timezone: String,
            recordDayId: RecordDayId, sourceSequence: Long, clientMessageId: UUID, now: Instant,
        ) = Message(id,userId,MessageRole.USER,content,occurredAt,timezone,recordDayId,sourceSequence,clientMessageId,createdAt=now)

        fun assistantMessage(
            id: MessageId, userId: UserId, content: String, occurredAt: Instant, timezone: String,
            recordDayId: RecordDayId, generationId: UUID, model: String,
            promptVersion: String, sourceSequence: Long,
        ) = Message(id,userId,MessageRole.ASSISTANT,content,occurredAt,timezone,recordDayId,sourceSequence,null,
            generationId,model,promptVersion,occurredAt)
    }
}
