package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

enum class JournalLifecycleStatus { DRAFT, CONFIRMED, OUTDATED }

data class Journal(
    val id: JournalId,
    val userId: UserId,
    val diaryDate: LocalDate,
    val lifecycleStatus: JournalLifecycleStatus,
    val title: String,
    // JSONB 원문. 구조 자체는 아직 미정(현재 {"body"} 최소 구조, 소비처가 생기면 확정, docs/DEVELOPMENT_STAGES.md 참고).
    val content: String,
    val currentRevision: Int,
    val version: Long,
    val confirmedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val purgeAfter: Instant? = null,
) {
    fun confirm(now: Instant): Journal = copy(lifecycleStatus = JournalLifecycleStatus.CONFIRMED, confirmedAt = now)

    fun markOutdated(): Journal = copy(lifecycleStatus = JournalLifecycleStatus.OUTDATED)

    companion object {
        fun draft(id: JournalId, userId: UserId, diaryDate: LocalDate, title: String, content: String): Journal =
            Journal(
                id = id,
                userId = userId,
                diaryDate = diaryDate,
                lifecycleStatus = JournalLifecycleStatus.DRAFT,
                title = title,
                content = content,
                currentRevision = 1,
                version = 0,
            )
    }
}
