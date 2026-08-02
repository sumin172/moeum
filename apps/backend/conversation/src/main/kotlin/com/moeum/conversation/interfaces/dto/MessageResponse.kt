package com.moeum.conversation.interfaces.dto

import com.moeum.conversation.domain.model.Message
import java.time.Instant
import java.util.UUID

data class MessageResponse(
    val id: UUID,
    val role: String,
    val content: String,
    val occurredAt: Instant,
    val responseStatus: String?,
) {
    companion object {
        fun from(message: Message): MessageResponse =
            MessageResponse(
                id = message.id.value,
                role = message.role.name,
                content = message.content,
                occurredAt = message.occurredAt,
                responseStatus = message.responseStatus?.name,
            )
    }
}
