package com.moeum.journal.support

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant
import java.time.LocalDate

// UNIQUE(user_id, diary_date), 낙관적 락(version), 선점 규칙(JobClaimSql)을 메모리에서 흉내 낸다.
class InMemoryGenerationJobRepository : GenerationJobRepository {
    val jobs = linkedMapOf<Any, GenerationJob>()

    override fun save(job: GenerationJob): GenerationJob {
        if (jobs.values.any { it.id != job.id && it.userId == job.userId && it.diaryDate == job.diaryDate }) {
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

    override fun findByUserIdAndDiaryDate(userId: UserId, diaryDate: LocalDate): GenerationJob? =
        jobs.values.find { it.userId == userId && it.diaryDate == diaryDate }

    override fun claimNext(now: Instant, leaseExpiresAt: Instant): GenerationJob? {
        val job = jobs.values
            .filter {
                (it.state.status == JobStatus.PENDING && !it.state.nextAttemptAt.isAfter(now)) ||
                    (it.state.status == JobStatus.PROCESSING && it.state.leaseExpiresAt?.isAfter(now) == false)
            }
            .minByOrNull { it.state.nextAttemptAt }
            ?: return null
        val claimed = job.copy(
            state = job.state.copy(
                status = JobStatus.PROCESSING,
                attemptCount = job.state.attemptCount + 1,
                leaseExpiresAt = leaseExpiresAt,
                version = job.state.version + 1,
            ),
        )
        jobs[job.id] = claimed
        return claimed
    }
}
