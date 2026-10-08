package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.GenerationJobId
import com.moeum.journal.domain.model.GenerationJobStatus
import com.moeum.journal.domain.model.JournalId
import com.moeum.kernel.UserId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class GenerationJobRepositoryImpl(
    private val jpaRepository: GenerationJobJpaRepository,
) : GenerationJobRepository {

    override fun save(job: GenerationJob): GenerationJob =
        jpaRepository.save(job.toEntity()).toDomain()

    override fun findPendingDue(now: Instant): List<GenerationJob> =
        jpaRepository.findByGenerationStatusAndScheduledAtLessThanEqual(GenerationJobStatus.PENDING, now)
            .map { it.toDomain() }

    override fun compareAndSetStatus(id: GenerationJobId, expected: GenerationJobStatus, updated: GenerationJobStatus): Boolean =
        jpaRepository.compareAndSetStatus(id.value, expected, updated) > 0
}

private fun GenerationJobJpaEntity.toDomain(): GenerationJob =
    GenerationJob(
        id = GenerationJobId(id),
        journalId = journalId?.let { JournalId(it) },
        userId = UserId(userId),
        diaryDate = diaryDate,
        windowStart = windowStart,
        windowEnd = windowEnd,
        scheduledAt = scheduledAt,
        timezoneAtScheduling = timezoneAtScheduling,
        generationTimeAtScheduling = generationTimeAtScheduling,
        status = generationStatus,
        attemptCount = attemptCount,
        provider = provider,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        generationId = generationId,
        generatedAt = generatedAt,
        errorCode = errorCode,
        createdAt = createdAt,
    )

private fun GenerationJob.toEntity(): GenerationJobJpaEntity =
    GenerationJobJpaEntity(
        id = id.value,
        journalId = journalId?.value,
        userId = userId.value,
        diaryDate = diaryDate,
        windowStart = windowStart,
        windowEnd = windowEnd,
        scheduledAt = scheduledAt,
        timezoneAtScheduling = timezoneAtScheduling,
        generationTimeAtScheduling = generationTimeAtScheduling,
        generationStatus = status,
        attemptCount = attemptCount,
        provider = provider,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        generationId = generationId,
        generatedAt = generatedAt,
        errorCode = errorCode,
        createdAt = createdAt,
    )
