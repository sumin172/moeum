package com.moeum.platform.job

import java.time.Instant

enum class JobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

// 재시도 가능한 비동기 작업(AI 응답 생성, 일기 생성 등)의 공통 실행 상태. 테이블은 각 모듈이 소유하고,
// 상태 전이와 선점 규칙(JobClaimSql)만 공유한다.
//
// PENDING ──claim──▶ PROCESSING ──성공──▶ COMPLETED
//    ▲                   │
//    └──실패, 재시도 남음──┤
//                        ├──실패, 재시도 소진──▶ FAILED ──사용자 재요청──▶ PENDING
//                        └──리스 만료(워커 중단)──▶ FAILED
//
// 자동 재시도는 살아 있는 워커가 직접 보고한 실패에만 적용한다. 리스가 만료된 PROCESSING은
// 주기적인 정리 트랜잭션이 FAILED로 바꾸고 다시 실행하지 않는다.
// 리스 기준 시각은 선점 시각(updated_at)이다. PROCESSING 동안에는 작업 행의 updated_at을 바꾸지 않는다.
data class JobState(
    val status: JobStatus,
    // 지금까지 시작한 시도 횟수. claim될 때 1 증가한다.
    val attemptCount: Int,
    // PENDING일 때 이 시각부터 claim할 수 있다.
    val nextAttemptAt: Instant,
    // claim과 모든 상태 저장마다 증가한다. 리스 만료로 정리됐거나 재요청된 뒤 도착한
    // 이전 시도의 늦은 결과 저장은 낙관적 락 충돌로 거부된다(fencing).
    val version: Long,
) {
    fun completed(): JobState = copy(status = JobStatus.COMPLETED)

    fun failed(policy: JobPolicy, now: Instant, failure: JobFailure): JobState {
        val nextAttemptAt = policy.nextAttemptAt(attemptCount, now, failure) ?: return failedPermanently()
        return copy(status = JobStatus.PENDING, nextAttemptAt = nextAttemptAt)
    }

    fun failedPermanently(): JobState = copy(status = JobStatus.FAILED)

    // 최종 실패. nextAttemptAt은 다시 시작할 수 있는 가장 이른 시각이 된다.
    fun failedUntil(reopenAt: Instant): JobState =
        copy(status = JobStatus.FAILED, nextAttemptAt = reopenAt)

    // 새 실행 주기를 시작한다. 같은 실행의 재시도가 아니므로 시도 횟수를 0으로 되돌린다.
    fun reopened(nextAttemptAt: Instant): JobState =
        copy(status = JobStatus.PENDING, attemptCount = 0, nextAttemptAt = nextAttemptAt)

    // 사용자 재요청(일기). 자동 재예약 판단에 시도 횟수를 쓰므로 초기화하지 않는다.
    fun restarted(now: Instant): JobState {
        check(status == JobStatus.FAILED) { "최종 실패한 작업만 다시 시작할 수 있습니다: status=$status" }
        return copy(status = JobStatus.PENDING, nextAttemptAt = now)
    }

    companion object {
        fun pending(nextAttemptAt: Instant): JobState =
            JobState(
                status = JobStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = nextAttemptAt,
                version = 0,
            )
    }
}
