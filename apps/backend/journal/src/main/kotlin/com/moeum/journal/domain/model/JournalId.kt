package com.moeum.journal.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class JournalId(val value: UUID) {
    companion object {
        fun generate(): JournalId = JournalId(UuidV7.generate())
    }
}
