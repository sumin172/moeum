package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobState
import com.moeum.platform.job.JobStatus
import java.time.Instant
import java.time.LocalDate

// 사용자에게 보여주는 실패 분류. 기술적인 원인(예외 종류)은 JobState.lastErrorCode에 남는다.
enum class ResponseFailureReason { QUOTA_EXCEEDED, GENERATION_FAILED }

// 유저 메시지 하나에 대한 AI 응답 생성 작업. 메시지와 같은 트랜잭션에서 만들어지므로, 비동기 실행이 유실돼도
// poller가 다시 집어 간다. 재시도(자동·사용자 요청)는 새 작업이 아니라 이 작업의 시도를 늘리는 것이다.
data class ResponseJob(
    val id: ResponseJobId,
    val userMessageId: MessageId,
    val userId: UserId,
    val dayDate: LocalDate,
    val state: JobState,
    val failureReason: ResponseFailureReason? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    // 사용자가 재시도를 요청할 수 있는지. quota 초과는 그 하루 안에서는 다시 시도해도 같은 결과라 제외한다.
    val retryable: Boolean
        get() = state.status == JobStatus.FAILED && failureReason == ResponseFailureReason.GENERATION_FAILED

    fun completed(now: Instant): ResponseJob = copy(state = state.completed(), failureReason = null, updatedAt = now)

    fun failed(errorCode: String, policy: JobPolicy, now: Instant): ResponseJob {
        val next = state.failed(errorCode, policy, now)
        val reason = if (next.status == JobStatus.FAILED) ResponseFailureReason.GENERATION_FAILED else null
        return copy(state = next, failureReason = reason, updatedAt = now)
    }

    fun failedPermanently(errorCode: String, now: Instant): ResponseJob =
        copy(state = state.failedPermanently(errorCode), failureReason = ResponseFailureReason.GENERATION_FAILED, updatedAt = now)

    fun restarted(now: Instant): ResponseJob = copy(state = state.restarted(now), failureReason = null, updatedAt = now)

    companion object {
        fun pending(userMessage: Message, now: Instant): ResponseJob =
            ResponseJob(
                id = ResponseJobId.generate(),
                userMessageId = userMessage.id,
                userId = userMessage.userId,
                dayDate = userMessage.dayDate,
                state = JobState.pending(nextAttemptAt = now),
                createdAt = now,
                updatedAt = now,
            )

        // quota를 넘긴 메시지는 LLM을 부르지 않고 처음부터 최종 실패로 만든다.
        fun quotaExceeded(userMessage: Message, now: Instant): ResponseJob {
            val pending = pending(userMessage, now)
            return pending.copy(
                state = pending.state.failedPermanently(QUOTA_EXCEEDED_ERROR_CODE),
                failureReason = ResponseFailureReason.QUOTA_EXCEEDED,
            )
        }

        const val QUOTA_EXCEEDED_ERROR_CODE = "QUOTA_EXCEEDED"
    }
}
