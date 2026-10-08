package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.GenerationJobId
import com.moeum.journal.domain.model.JournalId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobClaimSql
import com.moeum.platform.job.JobState
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

private const val TABLE = "journal.generation_jobs"

private const val CLAIM_NEXT_SQL =
    "UPDATE $TABLE ${JobClaimSql.CLAIM_SET} " +
        "WHERE id = (SELECT id FROM $TABLE WHERE ${JobClaimSql.CLAIMABLE} ${JobClaimSql.PICK_ONE}) RETURNING *"

@Component
class GenerationJobRepositoryImpl(
    private val jpaRepository: GenerationJobJpaRepository,
    private val entityManager: EntityManager,
) : GenerationJobRepository {

    override fun save(job: GenerationJob): GenerationJob =
        jpaRepository.save(job.toEntity()).toDomain()

    override fun findByUserIdAndDiaryDate(userId: UserId, diaryDate: LocalDate): GenerationJob? =
        jpaRepository.findByUserIdAndDiaryDate(userId.value, diaryDate)?.toDomain()

    @Transactional
    override fun claimNext(now: Instant, leaseExpiresAt: Instant): GenerationJob? =
        entityManager.createNativeQuery(CLAIM_NEXT_SQL, GenerationJobJpaEntity::class.java)
            .setParameter("now", now)
            .setParameter("leaseExpiresAt", leaseExpiresAt)
            .resultList
            .firstOrNull()
            ?.let { (it as GenerationJobJpaEntity).toDomain() }
}

private fun GenerationJobJpaEntity.toDomain(): GenerationJob =
    GenerationJob(
        id = GenerationJobId(id),
        journalId = journalId?.let { JournalId(it) },
        userId = UserId(userId),
        diaryDate = diaryDate,
        state = JobState(
            status = status,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            leaseExpiresAt = leaseExpiresAt,
            lastErrorCode = lastErrorCode,
            version = version,
        ),
        provider = provider,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        generationId = generationId,
        generatedAt = generatedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun GenerationJob.toEntity(): GenerationJobJpaEntity =
    GenerationJobJpaEntity(
        id = id.value,
        journalId = journalId?.value,
        userId = userId.value,
        diaryDate = diaryDate,
        status = state.status,
        attemptCount = state.attemptCount,
        nextAttemptAt = state.nextAttemptAt,
        leaseExpiresAt = state.leaseExpiresAt,
        lastErrorCode = state.lastErrorCode,
        version = state.version,
        provider = provider,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        generationId = generationId,
        generatedAt = generatedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
