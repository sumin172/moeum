package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class GenerationJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

data class GenerationJob(
    val id: GenerationJobId,
    val journalId: JournalId?,
    val userId: UserId,
    // Conversation의 dayDate(사용자 하루 경계 기준 하루)와 같은 값
    val diaryDate: LocalDate,
    // 이 하루가 끝나는 시각 — 이후 Executor가 claim할 수 있다
    val scheduledAt: Instant,
    val status: GenerationJobStatus,
    val attemptCount: Int,
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val generationId: UUID? = null,
    val generatedAt: Instant? = null,
    val errorCode: String? = null,
    val createdAt: Instant,
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
            status = GenerationJobStatus.COMPLETED,
            provider = provider,
            model = model,
            promptVersion = promptVersion,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            generationId = generationId,
            generatedAt = now,
        )

    fun failed(errorCode: String): GenerationJob =
        copy(status = GenerationJobStatus.FAILED, attemptCount = attemptCount + 1, errorCode = errorCode)

    companion object {
        fun pending(
            userId: UserId,
            diaryDate: LocalDate,
            scheduledAt: Instant,
            now: Instant,
        ): GenerationJob =
            GenerationJob(
                id = GenerationJobId.generate(),
                journalId = null,
                userId = userId,
                diaryDate = diaryDate,
                scheduledAt = scheduledAt,
                status = GenerationJobStatus.PENDING,
                attemptCount = 0,
                createdAt = now,
            )
    }
}
