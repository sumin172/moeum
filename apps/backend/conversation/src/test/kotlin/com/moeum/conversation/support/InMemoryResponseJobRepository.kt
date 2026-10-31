package com.moeum.conversation.support

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant

// 실제 구현의 선점 규칙(JobClaimSql), 실행 주기의 관측 상한, 낙관적 락(version)을 메모리에서 흉내 낸다.
class InMemoryResponseJobRepository(private val records: InMemoryRecordDayRepository) : ResponseJobRepository {
    val jobs = linkedMapOf<RecordDayId, ResponseJob>()

    override fun insert(job: ResponseJob) {
        check(job.recordDayId !in jobs)
        jobs[job.recordDayId] = job
    }

    override fun save(job: ResponseJob): ResponseJob {
        val current = jobs[job.recordDayId]
        if (current != null && current.state.version != job.state.version) {
            throw ObjectOptimisticLockingFailureException(ResponseJob::class.java, job.recordDayId.value)
        }
        val saved = job.copy(state = job.state.copy(version = job.state.version + if (current == null) 0 else 1))
        jobs[job.recordDayId] = saved
        return saved
    }

    override fun findByRecord(userId: UserId, recordDayId: RecordDayId): ResponseJob? =
        jobs[recordDayId]?.takeIf { it.userId == userId }

    override fun claimBatch(now: Instant, leaseExpiresAt: Instant, limit: Int): List<ResponseJob> =
        jobs.values
            .filter { isClaimable(it, now) }
            .sortedBy { if (it.state.status == JobStatus.PENDING) it.state.nextAttemptAt else it.state.leaseExpiresAt }
            .take(limit)
            .map { claimed(it, now, leaseExpiresAt) }

    private fun isClaimable(job: ResponseJob, now: Instant): Boolean {
        val state = job.state
        return (state.status == JobStatus.PENDING && !state.nextAttemptAt.isAfter(now)) ||
            (state.status == JobStatus.PROCESSING && state.leaseExpiresAt?.isAfter(now) == false)
    }

    private fun claimed(job: ResponseJob, now: Instant, leaseExpiresAt: Instant): ResponseJob {
        val observed = if (job.state.attemptCount == 0) records.days.getValue(job.recordDayId).sourceRevision
            else job.observedSourceRevision
        val claimed = job.copy(
            state = job.state.copy(
                status = JobStatus.PROCESSING,
                attemptCount = job.state.attemptCount + 1,
                leaseExpiresAt = leaseExpiresAt,
                version = job.state.version + 1,
            ),
            observedSourceRevision = observed,
            updatedAt = now,
        )
        jobs[job.recordDayId] = claimed
        return claimed
    }
}
