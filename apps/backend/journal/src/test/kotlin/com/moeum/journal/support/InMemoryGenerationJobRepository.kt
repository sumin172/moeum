package com.moeum.journal.support

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant

// UNIQUE(user_id, record_day), 낙관적 락(version), 선점 규칙(JobClaimSql)을 메모리에서 흉내 낸다.
class InMemoryGenerationJobRepository : GenerationJobRepository {
    val jobs = linkedMapOf<Any, GenerationJob>()

    override fun save(job: GenerationJob): GenerationJob {
        if (jobs.values.any { it.id != job.id && it.userId == job.userId && it.recordDayId == job.recordDayId }) {
            throw DataIntegrityViolationException("uq_journal_generation_jobs_user_diary_date")
        }
        val current = jobs[job.id]
        if (current != null && current.state.version != job.state.version) {
            throw ObjectOptimisticLockingFailureException(GenerationJob::class.java, job.id.value)
        }
        val saved = job.copy(state = job.state.copy(version = job.state.version + if (current == null) 0 else 1))
        jobs[job.id] = saved
        return saved
    }

    override fun lockClaim(job: GenerationJob) {
        val current = jobs[job.id]
        if (current == null || current.state.version != job.state.version || current.state.status != JobStatus.PROCESSING) {
            throw ObjectOptimisticLockingFailureException(GenerationJob::class.java,job.id.value)
        }
    }

    override fun findByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): GenerationJob? =
        jobs.values.find { it.userId == userId && it.recordDayId == recordDayId }

    override fun claimBatch(now: Instant, limit: Int): List<GenerationJob> =
        jobs.values
            .filter { it.state.status == JobStatus.PENDING && !it.state.nextAttemptAt.isAfter(now) }
            .sortedBy { it.state.nextAttemptAt }
            .take(limit)
            .map { job ->
                job.copy(
                    state = job.state.copy(
                        status = JobStatus.PROCESSING,
                        attemptCount = job.state.attemptCount + 1,
                        version = job.state.version + 1,
                    ),
                    updatedAt = now,
                ).also { jobs[it.id] = it }
            }

    override fun lockExpired(expiredBefore: Instant, limit: Int): List<GenerationJob> =
        jobs.values
            .filter { it.state.status == JobStatus.PROCESSING && !it.updatedAt.isAfter(expiredBefore) }
            .sortedBy { it.updatedAt }
            .take(limit)
}
