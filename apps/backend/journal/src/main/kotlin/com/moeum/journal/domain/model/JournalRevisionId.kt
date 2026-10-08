package com.moeum.journal.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class JournalRevisionId(val value: UUID) {
    companion object {
        fun generate(): JournalRevisionId = JournalRevisionId(UuidV7.generate())
    }
}
