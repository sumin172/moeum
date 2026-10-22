package com.moeum.platform.job

import java.time.Instant

enum class JobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

// 재시도 가능한 비동기 작업(AI 응답 생성, 일기 생성 등)의 공통 실행 상태. 테이블은 각 모듈이 소유하고,
// 상태 전이와 선점 규칙(JobClaimSql)만 공유한다.
//
// PENDING ──claim──▶ PROCESSING ──성공──▶ COMPLETED
//    ▲                   │
//    └──실패, 재시도 남음──┤
//                        └──실패, 재시도 소진──▶ FAILED ──사용자 재요청──▶ PENDING
//
// PROCESSING인 채로 리스가 만료되면(워커가 죽은 경우) 다른 워커가 다시 claim한다.
data class JobState(
    val status: JobStatus,
    // 지금까지 시작한 시도 횟수. claim될 때 1 증가한다.
    val attemptCount: Int,
    // PENDING일 때 이 시각부터 claim할 수 있다.
    val nextAttemptAt: Instant,
    // PROCESSING일 때 이 시각이 지나면 다른 워커가 다시 가져갈 수 있다.
    val leaseExpiresAt: Instant?,
    // claim마다 증가한다. 리스를 뺏긴 워커의 늦은 결과 저장은 낙관적 락 충돌로 거부된다(fencing).
    val version: Long,
) {
    fun completed(): JobState = copy(status = JobStatus.COMPLETED, leaseExpiresAt = null)

    fun failed(policy: JobPolicy, now: Instant, failure: JobFailure): JobState {
        val nextAttemptAt = policy.nextAttemptAt(attemptCount, now, failure) ?: return failedPermanently()
        return copy(status = JobStatus.PENDING, nextAttemptAt = nextAttemptAt, leaseExpiresAt = null)
    }

    fun failedPermanently(): JobState =
        copy(status = JobStatus.FAILED, leaseExpiresAt = null)

    // 자동 재예약 판단과 대화 작업의 누적 상한에 사용하므로 사용자 재요청에도 횟수를 초기화하지 않는다.
    fun restarted(now: Instant): JobState {
        check(status == JobStatus.FAILED) { "최종 실패한 작업만 다시 시작할 수 있습니다: status=$status" }
        return copy(status = JobStatus.PENDING, nextAttemptAt = now, leaseExpiresAt = null)
    }

    companion object {
        fun pending(nextAttemptAt: Instant): JobState =
            JobState(
                status = JobStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = nextAttemptAt,
                leaseExpiresAt = null,
                version = 0,
            )
    }
}
