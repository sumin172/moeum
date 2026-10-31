package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobFailure
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobState
import com.moeum.platform.job.JobStatus
import java.time.Duration
import java.time.Instant

// 기록 하나의 AI 응답 실행 상태. 기록당 한 행이며 연속 입력은 한 실행에서 함께 답한다.
// 답변 대상은 (마지막 ASSISTANT의 원본 순번, observedSourceRevision]의 USER다.
// observedSourceRevision은 실행 주기를 시작한 선점에서 관측한 기록의 원본 순번이고, 같은 주기의 재시도·리스 회수에서 유지한다.
// 모든 전이는 호출부가 기록 행 잠금 아래에서 결정한다.
data class ResponseJob(
    val recordDayId: RecordDayId,
    val userId: UserId,
    val state: JobState,
    val observedSourceRevision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    // 사용자 재요청 가능 여부. 재개 가능 시각 전이면 true여도 요청 시 대기를 안내한다.
    fun retryable(policy: JobPolicy, now: Instant, retention: Duration): Boolean =
        state.status == JobStatus.FAILED && !policy.manualRetryExhausted(state.attemptCount) &&
            now.isBefore(updatedAt.plus(retention))

    // 새 USER 접수. 대기·실행 중이면 그 실행이 끝난 뒤 이어서 답하므로 바꾸지 않는다(null).
    // 최종 실패 뒤의 접수는 미응답 입력에 대한 새 실행 주기를 열되 저장된 재개 가능 시각을 지킨다.
    fun inputAccepted(now: Instant): ResponseJob? = when (state.status) {
        JobStatus.PENDING, JobStatus.PROCESSING -> null
        JobStatus.COMPLETED -> copy(state = state.reopened(now), updatedAt = now)
        JobStatus.FAILED -> copy(state = state.reopened(maxOf(state.nextAttemptAt, now)), updatedAt = now)
    }

    // answeredThrough까지 답했다. 그 뒤에 접수된 입력이 있으면 이어서 다음 실행 주기를 연다.
    fun succeeded(answeredThrough: Long, sourceRevision: Long, now: Instant): ResponseJob =
        if (sourceRevision > answeredThrough) copy(state = state.reopened(now), updatedAt = now)
        else copy(state = state.completed(), updatedAt = now)

    fun failed(policy: JobPolicy, now: Instant, failure: JobFailure, sourceRevision: Long): ResponseJob {
        val next = policy.nextAttemptAt(state.attemptCount, now, failure)
            ?: return finallyFailed(policy.reopenAt(state.attemptCount, now, failure), sourceRevision, now)
        return copy(state = state.copy(status = JobStatus.PENDING, nextAttemptAt = next, leaseExpiresAt = null), updatedAt = now)
    }

    // 리스 회수로 누적 상한을 넘겨 호출 없이 끝내는 경우.
    fun exhausted(policy: JobPolicy, now: Instant, sourceRevision: Long): ResponseJob =
        finallyFailed(policy.reopenAt(state.attemptCount, now, null), sourceRevision, now)

    // 사용자 재요청은 같은 입력 범위의 재시도다. 관측 상한과 시도 횟수를 유지한다.
    fun restarted(now: Instant): ResponseJob = copy(state = state.restarted(now), updatedAt = now)

    // 이 실행 주기가 시작된 뒤 접수된 입력이 있으면 접수 시점과 관계없이 FAILED 뒤 접수와 같게 재개한다.
    private fun finallyFailed(reopenAt: Instant, sourceRevision: Long, now: Instant): ResponseJob =
        if (sourceRevision > observedSourceRevision) copy(state = state.reopened(reopenAt), updatedAt = now)
        else copy(state = state.failedUntil(reopenAt), updatedAt = now)

    companion object {
        fun pending(userId: UserId, recordDayId: RecordDayId, now: Instant): ResponseJob =
            ResponseJob(
                recordDayId = recordDayId,
                userId = userId,
                state = JobState.pending(nextAttemptAt = now),
                observedSourceRevision = 0,
                createdAt = now,
                updatedAt = now,
            )
    }
}

// 미응답 USER를 오래된 순으로 한 호출의 메시지 수·글자 예산 안까지 이번 답변 대상으로 고른다.
// 메시지 상한이 예산 이하라 첫 입력은 항상 포함된다. 과거 문맥은 남은 수·예산을 사용한다.
fun selectResponseTargets(unanswered: List<Message>, budgetChars: Int, maxMessages: Int): List<Message> {
    var used = 0
    return unanswered.take(maxMessages).takeWhile { message ->
        used += message.content.length
        used <= budgetChars
    }.ifEmpty { unanswered.take(1) }
}
