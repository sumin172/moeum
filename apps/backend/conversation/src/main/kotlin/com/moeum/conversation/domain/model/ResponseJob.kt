package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobFailure
import com.moeum.platform.job.JobState
import com.moeum.platform.job.JobStatus
import java.time.Instant

// 사용자에게 보여주는 실패 분류는 작업 상태에서 계산한다.
enum class ResponseFailureReason { GENERATION_FAILED }

// 유저 메시지 하나에 대한 AI 응답 생성 작업. 메시지와 같은 트랜잭션에서 만들어지므로, 비동기 실행이 유실돼도
// poller가 다시 집어 간다. 재시도(자동·사용자 요청)는 새 작업이 아니라 이 작업의 시도를 늘리는 것이다.
data class ResponseJob(
    val id: ResponseJobId,
    val userMessageId: MessageId,
    val userId: UserId,
    val recordDayId: RecordDayId,
    val state: JobState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val inputSourceRevision: Long,
) {
    // 최종 생성 실패만 재시도 대상이다. 후속 작업과의 순서는 요청 시 다시 검증한다.
    fun retryable(policy: JobPolicy): Boolean =
        state.status == JobStatus.FAILED && policy.manualRetryAt(state.attemptCount, updatedAt) != null

    val failureReason: ResponseFailureReason?
        get() = if (state.status == JobStatus.FAILED) ResponseFailureReason.GENERATION_FAILED else null

    fun completed(now: Instant): ResponseJob = copy(state = state.completed(), updatedAt = now)

    fun failed(policy: JobPolicy, now: Instant, failure: JobFailure): ResponseJob {
        val next = state.failed(policy, now, failure)
        return copy(state = next, updatedAt = now)
    }

    fun failedPermanently(now: Instant): ResponseJob =
        copy(state = state.failedPermanently(), updatedAt = now)

    fun restarted(now: Instant): ResponseJob = copy(state = state.restarted(now), updatedAt = now)

    companion object {
        fun pending(userMessage: Message, now: Instant): ResponseJob =
            ResponseJob(
                id = ResponseJobId.generate(),
                userMessageId = userMessage.id,
                userId = userMessage.userId,
                recordDayId = userMessage.recordDayId,
                inputSourceRevision = userMessage.sourceSequence,
                state = JobState.pending(nextAttemptAt = now),
                createdAt = now,
                updatedAt = now,
            )

    }
}
