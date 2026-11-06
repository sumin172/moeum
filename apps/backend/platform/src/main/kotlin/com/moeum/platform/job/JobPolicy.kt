package com.moeum.platform.job

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

data class JobPolicy(
    // 자동으로 시도하는 최대 횟수(첫 시도 포함)
    val maxAttempts: Int,
    // n번째 시도가 실패한 뒤 기다릴 시간. 목록보다 시도가 많으면 마지막 값을 쓴다.
    val backoff: List<Duration>,
    // claim한 워커가 이 시간 안에 끝내지 못하면 중단된 것으로 보고 FAILED로 정리한다(JobState 참고).
    // 실행 한 번의 외부 호출 타임아웃 합계보다 넉넉해야 한다. 정상이지만 느린 작업도 실패로 정리되기 때문이다.
    val lease: Duration,
    // 대화 작업에 적용하는 자동·수동 시도 누적 상한. 일기 작업은 집행하지 않는다.
    val maxTotalAttempts: Int = maxAttempts + 2,
    val manualRetryCooldown: Duration = Duration.ofMinutes(1),
    val rateLimitBackoff: Duration = Duration.ofMinutes(1),
    // 여러 작업이 같은 순간 재시도로 몰리지 않도록 backoff에 더하는 무작위 비율
    val jitterRatio: Double = 0.2,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts는 1 이상이어야 합니다" }
        require(backoff.isNotEmpty()) { "backoff는 비어 있을 수 없습니다" }
        require(maxTotalAttempts >= maxAttempts)
        require(!manualRetryCooldown.isNegative && !rateLimitBackoff.isNegative)
    }

    // attemptCount번째 시도가 실패했을 때 다음 시도 시각. 더 시도하지 않으면 null.
    fun nextAttemptAt(attemptCount: Int, now: Instant, random: Random = Random.Default): Instant? {
        if (attemptCount >= maxAttempts) return null
        val base = backoff[(attemptCount - 1).coerceIn(0, backoff.lastIndex)]
        val jitterMillis = (base.toMillis() * jitterRatio * random.nextDouble()).toLong()
        return now.plus(base).plusMillis(jitterMillis)
    }

    fun nextAttemptAt(attemptCount: Int, now: Instant, failure: JobFailure): Instant? {
        if (failure.kind == JobFailureKind.PERMANENT ||
            (failure.kind == JobFailureKind.INVALID_OUTPUT && attemptCount >= 2)) return null
        val ordinary = nextAttemptAt(attemptCount, now) ?: return null
        if (failure.kind != JobFailureKind.RATE_LIMITED) return ordinary
        val providerDelay = failure.retryAfter?.takeIf { !it.isNegative } ?: Duration.ZERO
        return maxOf(ordinary, now.plus(rateLimitBackoff), now.plus(providerDelay))
    }

    // 최종 실패한 작업을 다시 시작할 수 있는 가장 이른 시각. 자동 재개와 사용자 재요청이 같은 하한을 지킨다.
    // failure가 없으면(리스 만료) 일반 backoff와 수동 대기만 적용한다.
    fun reopenAt(attemptCount: Int, failedAt: Instant, failure: JobFailure?): Instant {
        val ordinary = failedAt.plus(backoff[(attemptCount - 1).coerceIn(0, backoff.lastIndex)])
        val earliest = maxOf(ordinary, failedAt.plus(manualRetryCooldown))
        if (failure?.kind != JobFailureKind.RATE_LIMITED) return earliest
        val providerDelay = failure.retryAfter?.takeIf { !it.isNegative } ?: Duration.ZERO
        return maxOf(earliest, failedAt.plus(rateLimitBackoff), failedAt.plus(providerDelay))
    }

    fun manualRetryExhausted(attemptCount: Int): Boolean = attemptCount >= maxTotalAttempts

    // 이 시각 이전에 선점된 PROCESSING은 리스가 만료됐다.
    fun leaseExpiredBefore(now: Instant): Instant = now.minus(lease)
}
