package com.moeum.conversation.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class MomentSetId(val value: UUID) {
    companion object {
        fun generate(): MomentSetId = MomentSetId(UuidV7.generate())

        fun of(value: String): MomentSetId = MomentSetId(UUID.fromString(value))
    }
}
