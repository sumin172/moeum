package com.moeum.conversation.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class MessageId(val value: UUID) {
    companion object {
        fun generate(): MessageId = MessageId(UuidV7.generate())

        fun of(value: String): MessageId = MessageId(UUID.fromString(value))
    }
}
