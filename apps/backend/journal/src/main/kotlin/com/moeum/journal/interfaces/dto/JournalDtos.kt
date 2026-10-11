package com.moeum.journal.interfaces.dto

import com.moeum.journal.application.query.JournalDay
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.Journal
import com.moeum.platform.job.JobStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class EditJournalRequest(
    val title: String,
    val body: String,
    // 마지막으로 받은 일기의 version — 다른 기기에서 먼저 바뀌었으면 409
    val version: Long,
)

data class ConfirmJournalRequest(
    val version: Long,
)

data class JournalResponse(
    val recordDayId: UUID,
    val displayDate: LocalDate,
    val title: String,
    val body: String,
    // DRAFT | CONFIRMED
    val lifecycleStatus: String,
    val revision: Int,
    val version: Long,
    val confirmedAt: Instant?,
    // 이 사용자가 직접 고칠 수 있는지(요금제). false면 수정 UI를 숨기거나 구독 안내를 띄운다.
    val editable: Boolean,
) {
    companion object {
        fun from(journal: Journal, editable: Boolean): JournalResponse =
            JournalResponse(
                recordDayId = journal.recordDayId.value,
                displayDate = journal.displayDate,
                title = journal.title,
                body = journal.body,
                lifecycleStatus = journal.lifecycleStatus.name,
                revision = journal.currentRevision,
                version = journal.version,
                confirmedAt = journal.confirmedAt,
                editable = editable,
            )
    }
}

data class GenerationStatusResponse(
    // PENDING(하루가 끝나기를 기다리는 중 포함) | PROCESSING | COMPLETED | FAILED
    val status: String,
    // true면 POST /api/journals/{displayDate}/generation-attempts로 다시 요청할 수 있다
    val retryable: Boolean,
) {
    companion object {
        fun from(job: GenerationJob): GenerationStatusResponse =
            GenerationStatusResponse(status = job.state.status.name, retryable = job.state.status == JobStatus.FAILED)
    }
}

data class JournalDayResponse(
    val recordDayId: UUID,
    // 아직 생성되지 않았으면 null
    val journal: JournalResponse?,
    // 그 하루에 대화가 없어 생성할 일기가 없으면 null
    val generation: GenerationStatusResponse?,
) {
    companion object {
        fun from(day: JournalDay): JournalDayResponse =
            JournalDayResponse(
                recordDayId = day.recordDayId.value,
                journal = day.journal?.let { JournalResponse.from(it, day.editable) },
                generation = day.generationJob?.let { GenerationStatusResponse.from(it) },
            )
    }
}
