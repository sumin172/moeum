package com.moeum.journal.domain.model

import java.time.Instant

enum class JournalRevisionEditor { USER, AI }

data class JournalRevision(
    val id: JournalRevisionId,
    val journalId: JournalId,
    val revisionNo: Int,
    val title: String,
    val content: String,
    val editedBy: JournalRevisionEditor,
    val createdAt: Instant,
) {
    companion object {
        fun of(
            id: JournalRevisionId,
            journalId: JournalId,
            revisionNo: Int,
            title: String,
            content: String,
            editedBy: JournalRevisionEditor,
            now: Instant,
        ): JournalRevision =
            JournalRevision(
                id = id,
                journalId = journalId,
                revisionNo = revisionNo,
                title = title,
                content = content,
                editedBy = editedBy,
                createdAt = now,
            )
    }
}
