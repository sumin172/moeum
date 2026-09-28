package com.moeum.conversation.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class MomentId(val value: UUID) {
    companion object {
        fun generate(): MomentId = MomentId(UuidV7.generate())

        fun of(value: String): MomentId = MomentId(UUID.fromString(value))
    }
}
