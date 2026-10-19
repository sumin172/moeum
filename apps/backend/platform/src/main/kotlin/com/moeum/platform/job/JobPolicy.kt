package com.moeum.platform.job

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

data class JobPolicy(
    // 자동으로 시도하는 최대 횟수(첫 시도 포함)
    val maxAttempts: Int,
    // n번째 시도가 실패한 뒤 기다릴 시간. 목록보다 시도가 많으면 마지막 값을 쓴다.
    val backoff: List<Duration>,
    // claim한 워커가 이 시간 안에 끝내지 못하면 죽은 것으로 보고 다른 워커가 다시 가져간다.
    // 외부 호출 타임아웃보다 넉넉해야 한다.
    val lease: Duration,
    // 자동·수동·리스 복구를 합친 공급자 호출 상한.
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

    // 리스 만료로 다시 claim되면서 시도 횟수가 상한을 넘은 경우 — 실행하지 않고 최종 실패로 끝낸다.
    fun isExhausted(attemptCount: Int): Boolean = attemptCount > maxTotalAttempts

    fun manualRetryAt(attemptCount: Int, failedAt: Instant): Instant? =
        if (attemptCount >= maxTotalAttempts) null else failedAt.plus(manualRetryCooldown)

    fun leaseExpiresAt(now: Instant): Instant = now.plus(lease)
}
