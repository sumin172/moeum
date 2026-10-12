package com.moeum.journal.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class GenerationJobId(val value: UUID) {
    companion object {
        fun generate(): GenerationJobId = GenerationJobId(UuidV7.generate())
    }
}
