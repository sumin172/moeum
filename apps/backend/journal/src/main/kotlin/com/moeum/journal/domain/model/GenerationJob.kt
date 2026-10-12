package com.moeum.journal.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 마감된 기록당 하나의 최초 일기 생성 작업. 날짜는 표시 속성이고 recordDayId가 멱등키다.
data class GenerationJob(
    val id: GenerationJobId,
    val journalId: JournalId?,
    val userId: UserId,
    // 기록에 고정된 표시 날짜. 생성 작업의 키는 recordDayId다.
    val recordDayId: RecordDayId,
    val displayDate: LocalDate,
    val state: JobState,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val generationId: UUID? = null,
    val generatedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun completed(
        journalId: JournalId,
        provider: String,
        model: String,
        promptVersion: String,
        inputTokens: Int,
        outputTokens: Int,
        generationId: UUID,
        now: Instant,
    ): GenerationJob =
        copy(
            journalId = journalId,
            state = state.completed(),
            provider = provider,
            model = model,
            promptVersion = promptVersion,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            generationId = generationId,
            generatedAt = now,
            updatedAt = now,
        )

    fun failed(errorCode: String, policy: JobPolicy, now: Instant): GenerationJob =
        copy(state = state.failed(errorCode, policy, now), updatedAt = now)

    fun failedPermanently(errorCode: String, now: Instant): GenerationJob =
        copy(state = state.failedPermanently(errorCode), updatedAt = now)

    // 재시도를 모두 소진한 작업을 사용자가 다시 요청한 경우
    fun restarted(now: Instant): GenerationJob = copy(state = state.restarted(now), updatedAt = now)

    companion object {
        fun pending(userId: UserId, recordDayId: RecordDayId, displayDate: LocalDate, dayEnd: Instant, now: Instant): GenerationJob =
            GenerationJob(
                id = GenerationJobId.generate(),
                journalId = null,
                userId = userId,
                recordDayId = recordDayId,
                displayDate = displayDate,
                state = JobState.pending(nextAttemptAt = dayEnd),
                createdAt = now,
                updatedAt = now,
            )
    }
}
