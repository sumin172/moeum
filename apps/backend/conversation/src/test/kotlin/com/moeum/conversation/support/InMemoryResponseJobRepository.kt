package com.moeum.conversation.support

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.model.ResponseJobId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant

// 실제 구현의 선점 규칙(JobClaimSql)과 낙관적 락(version)을 메모리에서 흉내 낸다.
class InMemoryResponseJobRepository : ResponseJobRepository {
    val jobs = linkedMapOf<ResponseJobId, ResponseJob>()

    override fun save(job: ResponseJob): ResponseJob {
        val current = jobs[job.id]
        if (current != null && current.state.version != job.state.version) {
            throw ObjectOptimisticLockingFailureException(ResponseJob::class.java, job.id.value)
        }
        val saved = job.copy(state = job.state.copy(version = job.state.version + if (current == null) 0 else 1))
        jobs[job.id] = saved
        return saved
    }

    override fun findByUserIdAndUserMessageId(userId: UserId, userMessageId: MessageId): ResponseJob? =
        jobs.values.find { it.userId == userId && it.userMessageId == userMessageId }

    override fun findAllByUserIdAndUserMessageIdIn(userId: UserId, userMessageIds: Collection<MessageId>): List<ResponseJob> =
        jobs.values.filter { it.userId == userId && it.userMessageId in userMessageIds }

    override fun claimNext(now: Instant, leaseExpiresAt: Instant): ResponseJob? =
        jobs.values
            .filter { isClaimable(it, now) }
            .minByOrNull { it.state.nextAttemptAt }
            ?.let { claimed(it, now, leaseExpiresAt) }

    override fun claim(id: ResponseJobId, now: Instant, leaseExpiresAt: Instant): ResponseJob? =
        jobs[id]
            ?.takeIf { it.state.status == JobStatus.PENDING && !it.state.nextAttemptAt.isAfter(now) }
            ?.let { claimed(it, now, leaseExpiresAt) }

    private fun isClaimable(job: ResponseJob, now: Instant): Boolean {
        val state = job.state
        return (state.status == JobStatus.PENDING && !state.nextAttemptAt.isAfter(now)) ||
            (state.status == JobStatus.PROCESSING && state.leaseExpiresAt?.isAfter(now) == false)
    }

    private fun claimed(job: ResponseJob, now: Instant, leaseExpiresAt: Instant): ResponseJob {
        val claimed = job.copy(
            state = job.state.copy(
                status = JobStatus.PROCESSING,
                attemptCount = job.state.attemptCount + 1,
                leaseExpiresAt = leaseExpiresAt,
                version = job.state.version + 1,
            ),
            updatedAt = now,
        )
        jobs[job.id] = claimed
        return claimed
    }
}
