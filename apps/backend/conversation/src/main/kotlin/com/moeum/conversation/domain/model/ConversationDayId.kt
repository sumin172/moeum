package com.moeum.conversation.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class ConversationDayId(val value: UUID) {
    companion object {
        fun generate(): ConversationDayId = ConversationDayId(UuidV7.generate())

        fun of(value: String): ConversationDayId = ConversationDayId(UUID.fromString(value))
    }
}
