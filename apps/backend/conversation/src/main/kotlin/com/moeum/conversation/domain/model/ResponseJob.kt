package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobFailure
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobState
import com.moeum.platform.job.JobStatus
import java.time.Instant

// 기록 하나의 AI 응답 실행 상태. 기록당 한 행이며 연속 입력은 한 실행에서 함께 답한다.
// 답변 대상은 (마지막 ASSISTANT의 원본 순번, observedSourceRevision]의 USER다.
// observedSourceRevision은 실행 주기를 시작한 선점에서 관측한 기록의 원본 순번이고, 같은 주기의 자동 재시도에서 유지한다.
// 자동 재시도가 남은 주기에 새 입력이 접수되면 새 실행 주기로 합쳐 다시 시도한다. 시도 상한은 입력마다 다시 주어진다.
// 모든 전이는 호출부가 기록 행 잠금 아래에서 결정한다.
data class ResponseJob(
    val recordDayId: RecordDayId,
    val userId: UserId,
    val state: JobState,
    val observedSourceRevision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    // 새 USER 접수. 바꿀 것이 없으면 null.
    // 첫 시도 대기 중이면 선점이 이 입력까지 담는다. 실행 중이면 그 실행의 결과(succeeded·failed)에서 판단한다.
    // 재시도 대기 중이면 새 실행 주기로 합쳐 다시 시도하되 예약된 재시도 시각(429 대기 포함)을 지킨다.
    // 최종 실패 뒤의 접수는 미응답 입력에 대한 새 실행 주기를 열되 저장된 재개 가능 시각을 지킨다.
    fun inputAccepted(now: Instant): ResponseJob? = when (state.status) {
        JobStatus.PROCESSING -> null
        JobStatus.PENDING -> if (state.attemptCount == 0) null else copy(state = state.reopened(state.nextAttemptAt), updatedAt = now)
        JobStatus.COMPLETED -> copy(state = state.reopened(now), updatedAt = now)
        JobStatus.FAILED -> copy(state = state.reopened(maxOf(state.nextAttemptAt, now)), updatedAt = now)
    }

    // answeredThrough까지 답했다. 그 뒤에 접수된 입력이 있으면 이어서 다음 실행 주기를 연다.
    fun succeeded(answeredThrough: Long, sourceRevision: Long, now: Instant): ResponseJob =
        if (sourceRevision > answeredThrough) copy(state = state.reopened(now), updatedAt = now)
        else copy(state = state.completed(), updatedAt = now)

    // 재시도가 남았으면 예약한다. 실행 중 접수된 입력이 있으면 새 실행 주기로 합쳐 다시 시도한다.
    fun failed(policy: JobPolicy, now: Instant, failure: JobFailure, sourceRevision: Long): ResponseJob {
        val next = policy.nextAttemptAt(state.attemptCount, now, failure)
            ?: return finallyFailed(policy.reopenAt(state.attemptCount, now, failure), sourceRevision, now)
        if (sourceRevision > observedSourceRevision) return copy(state = state.reopened(next), updatedAt = now)
        return copy(state = state.copy(status = JobStatus.PENDING, nextAttemptAt = next), updatedAt = now)
    }

    // 실패한 시도가 있는 주기에 새 입력이 합쳐져 새 실행 주기가 열렸는가(진단 로그용)
    fun mergedInputFrom(previous: ResponseJob): Boolean =
        previous.state.attemptCount > 0 && state.status == JobStatus.PENDING && state.attemptCount == 0

    // 리스가 만료된(워커가 중단된) 실행은 다시 실행하지 않는다. 이 실행 주기 뒤에 접수된 입력이 없으면 FAILED로 두고,
    // 있으면 다른 최종 실패와 같이 그 입력에 대한 새 실행 주기를 연다.
    fun leaseExpired(policy: JobPolicy, now: Instant, sourceRevision: Long): ResponseJob =
        finallyFailed(policy.reopenAt(state.attemptCount, now, null), sourceRevision, now)

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
