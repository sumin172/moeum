package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobState
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 사용자 하루 하나의 일기 생성 작업. 첫 시도 시각(state.nextAttemptAt)은 그 하루가 끝나는 시각이다.
data class GenerationJob(
    val id: GenerationJobId,
    val journalId: JournalId?,
    val userId: UserId,
    // Conversation의 dayDate(사용자 하루 경계 기준 하루)와 같은 값
    val diaryDate: LocalDate,
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

    companion object {
        fun pending(userId: UserId, diaryDate: LocalDate, dayEnd: Instant, now: Instant): GenerationJob =
            GenerationJob(
                id = GenerationJobId.generate(),
                journalId = null,
                userId = userId,
                diaryDate = diaryDate,
                state = JobState.pending(nextAttemptAt = dayEnd),
                createdAt = now,
                updatedAt = now,
            )
    }
}
