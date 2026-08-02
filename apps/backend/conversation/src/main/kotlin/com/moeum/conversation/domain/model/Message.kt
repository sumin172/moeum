package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class MessageRole { USER, ASSISTANT }

enum class MessageResponseStatus { PENDING, PROCESSING, COMPLETED, FAILED }

data class Message(
    val id: MessageId,
    val conversationDayId: ConversationDayId,
    val userId: UserId,
    val role: MessageRole,
    val content: String,
    val occurredAt: Instant,
    val timezone: String,
    val localDate: LocalDate,
    val clientMessageId: UUID?,
    val responseStatus: MessageResponseStatus?,
    val generationId: UUID? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val deletedAt: Instant? = null,
) {
    companion object {
        fun userMessage(
            id: MessageId,
            conversationDayId: ConversationDayId,
            userId: UserId,
            content: String,
            occurredAt: Instant,
            timezone: String,
            localDate: LocalDate,
            clientMessageId: UUID,
        ): Message =
            Message(
                id = id,
                conversationDayId = conversationDayId,
                userId = userId,
                role = MessageRole.USER,
                content = content,
                occurredAt = occurredAt,
                timezone = timezone,
                localDate = localDate,
                clientMessageId = clientMessageId,
                responseStatus = MessageResponseStatus.PENDING,
            )
    }
}
