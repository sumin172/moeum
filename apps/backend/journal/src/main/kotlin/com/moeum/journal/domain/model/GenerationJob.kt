package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

enum class GenerationJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

data class GenerationJob(
    val id: GenerationJobId,
    val journalId: JournalId?,
    val userId: UserId,
    val diaryDate: LocalDate,
    val windowStart: Instant,
    val windowEnd: Instant,
    val scheduledAt: Instant,
    // Planning 시점에 확정되며, 이후 preference/timezone 변경으로 재계산되지 않는다(docs/ARCHITECTURE.md invariant 3).
    val timezoneAtScheduling: String,
    val generationTimeAtScheduling: LocalTime,
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
            windowStart: Instant,
            windowEnd: Instant,
            scheduledAt: Instant,
            timezoneAtScheduling: String,
            generationTimeAtScheduling: LocalTime,
            now: Instant,
        ): GenerationJob =
            GenerationJob(
                id = GenerationJobId.generate(),
                journalId = null,
                userId = userId,
                diaryDate = diaryDate,
                windowStart = windowStart,
                windowEnd = windowEnd,
                scheduledAt = scheduledAt,
                timezoneAtScheduling = timezoneAtScheduling,
                generationTimeAtScheduling = generationTimeAtScheduling,
                status = GenerationJobStatus.PENDING,
                attemptCount = 0,
                createdAt = now,
            )
    }
}
