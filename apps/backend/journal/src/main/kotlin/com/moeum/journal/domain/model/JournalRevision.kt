package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import java.time.Instant

enum class JournalRevisionEditor { USER, AI }

data class JournalRevision(
    val id: JournalRevisionId,
    val journalId: JournalId,
    // 사용자 소유 데이터는 user_id를 직접 갖는다(탈퇴 연쇄 삭제·사용자별 export를 join 없이)
    val userId: UserId,
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
            userId: UserId,
            revisionNo: Int,
            title: String,
            content: String,
            editedBy: JournalRevisionEditor,
            now: Instant,
        ): JournalRevision =
            JournalRevision(
                id = id,
                journalId = journalId,
                userId = userId,
                revisionNo = revisionNo,
                title = title,
                content = content,
                editedBy = editedBy,
                createdAt = now,
            )
    }
}
