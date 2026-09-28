package com.moeum.conversation.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class MomentExtractionJobId(val value: UUID) {
    companion object {
        fun generate(): MomentExtractionJobId = MomentExtractionJobId(UuidV7.generate())

        fun of(value: String): MomentExtractionJobId = MomentExtractionJobId(UUID.fromString(value))
    }
}
