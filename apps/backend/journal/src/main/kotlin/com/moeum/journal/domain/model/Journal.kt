package com.moeum.journal.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

enum class JournalLifecycleStatus {
    DRAFT,
    CONFIRMED
}

data class Journal(
    val id: JournalId,
    val userId: UserId,
    val recordDayId: RecordDayId,
    val displayDate: LocalDate,
    val lifecycleStatus: JournalLifecycleStatus,
    val title: String,
    // 현재는 journals.content(JSONB) {"body"}로 저장한다. 재설계 R4에서 TEXT로 단순화한다.
    val body: String,
    val currentRevision: Int,
    // 낙관적 락. 클라이언트는 받은 version을 수정·확정 요청에 그대로 돌려보낸다(다른 기기에서 먼저 바꿨으면 충돌).
    val version: Long,
    val sourceRevision: Long,
    val confirmedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val purgeAfter: Instant? = null,
) {
    // 사용자가 고친 내용은 다시 확정해야 한다 — 확정 상태에서 고쳐도 DRAFT로 돌아간다.
    fun edited(title: String, body: String): Journal =
        copy(
            title = title,
            body = body,
            currentRevision = currentRevision + 1,
            lifecycleStatus = JournalLifecycleStatus.DRAFT,
            confirmedAt = null,
        )

    fun confirmed(now: Instant): Journal = copy(lifecycleStatus = JournalLifecycleStatus.CONFIRMED, confirmedAt = now)

    val isConfirmed: Boolean get() = lifecycleStatus == JournalLifecycleStatus.CONFIRMED


    companion object {
        fun draft(
            id: JournalId,
            userId: UserId,
            recordDayId: RecordDayId,
            displayDate: LocalDate,
            title: String,
            body: String,
            sourceRevision: Long,
        ): Journal =
            Journal(
                id = id,
                userId = userId,
                recordDayId = recordDayId,
                displayDate = displayDate,
                lifecycleStatus = JournalLifecycleStatus.DRAFT,
                title = title,
                body = body,
                currentRevision = 1,
                version = 0,
                sourceRevision = sourceRevision,
            )
    }
}
