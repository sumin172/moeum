package com.moeum.platform.job

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import com.moeum.platform.llm.LlmException

class JobStateTest {

    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val policy = JobPolicy(
        maxAttempts = 3,
        backoff = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
        lease = Duration.ofMinutes(2),
        jitterRatio = 0.0,
    )

    // claim이 하는 일(시도 횟수 증가, PROCESSING 전이)을 흉내 낸다
    private fun JobState.claimed(): JobState =
        copy(status = JobStatus.PROCESSING, attemptCount = attemptCount + 1, leaseExpiresAt = policy.leaseExpiresAt(now), version = version + 1)

    @Test
    fun `실패하면 재시도가 남은 동안 backoff 뒤로 다시 예약되고, 소진되면 최종 실패한다`() {
        val first = JobState.pending(now).claimed().failed(policy, now, JobFailure(JobFailureKind.TRANSIENT))
        assertThat(first.status).isEqualTo(JobStatus.PENDING)
        assertThat(first.nextAttemptAt).isEqualTo(now.plusSeconds(10))

        val second = first.claimed().failed(policy, now, JobFailure(JobFailureKind.TRANSIENT))
        assertThat(second.status).isEqualTo(JobStatus.PENDING)
        assertThat(second.nextAttemptAt).isEqualTo(now.plusSeconds(60))

        // backoff 목록보다 시도가 많아도 마지막 값을 쓰지만, 여기서는 3번째가 마지막 시도라 최종 실패
        val third = second.claimed().failed(policy, now, JobFailure(JobFailureKind.TRANSIENT))
        assertThat(third.status).isEqualTo(JobStatus.FAILED)
        assertThat(third.attemptCount).isEqualTo(3)
        assertThat(third.leaseExpiresAt).isNull()
    }

    @Test
    fun `backoff에 지터를 더해 재시도가 한 순간에 몰리지 않게 한다`() {
        val jittered = policy.copy(jitterRatio = 0.5)

        val next = jittered.nextAttemptAt(attemptCount = 1, now = now, random = Random(42))!!

        assertThat(next).isAfterOrEqualTo(now.plusSeconds(10)).isBeforeOrEqualTo(now.plusSeconds(15))
    }

    @Test
    fun `리스 만료로 다시 잡혀 시도 횟수가 상한을 넘으면 소진된 것으로 본다`() {
        assertThat(policy.isExhausted(3)).isFalse()
        assertThat(policy.isExhausted(5)).isFalse()
        assertThat(policy.isExhausted(6)).isTrue()
    }

    @Test
    fun `최종 실패한 작업만 다시 시작하고 누적 시도 횟수는 유지한다`() {
        val failed = JobState.pending(now).claimed().failedPermanently()
        val restarted = failed.restarted(now.plusSeconds(100))

        assertThat(restarted.status).isEqualTo(JobStatus.PENDING)
        assertThat(restarted.attemptCount).isEqualTo(1)
        assertThat(restarted.nextAttemptAt).isEqualTo(now.plusSeconds(100))

        assertThatThrownBy { JobState.pending(now).restarted(now) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `인증 오류는 중단하고 호출 제한은 공급자 대기 시각 이후로 예약한다`() {
        val claimed = JobState.pending(now).claimed()
        assertThat(claimed.failed(policy, now, JobFailure.classify(LlmException("sanitized", errorCode = "HTTP_401"))).status)
            .isEqualTo(JobStatus.FAILED)

        val limited = claimed.failed(policy, now, JobFailure.classify(
            LlmException("sanitized", errorCode = "HTTP_429", retryAfter = Duration.ofMinutes(3))))
        assertThat(limited.nextAttemptAt).isEqualTo(now.plus(Duration.ofMinutes(3)))
    }

    @Test
    fun `Spring 예외에 감싸진 일시적 DB 연결 오류는 재시도한다`() {
        val failure = JobFailure.classify(IllegalStateException("DB 연결 실패",
            java.sql.SQLTransientConnectionException("connection pool timeout")))

        assertThat(failure.kind).isEqualTo(JobFailureKind.TRANSIENT)
        assertThat(JobState.pending(now).claimed().failed(policy, now, failure).status).isEqualTo(JobStatus.PENDING)
    }

    @Test
    fun `출력 오류는 한 번만 자동 재시도하고 수동 재시도는 간격과 누적 횟수로 제한한다`() {
        val invalid = JobFailure(JobFailureKind.INVALID_OUTPUT)
        val first = JobState.pending(now).claimed().failed(policy, now, invalid)
        assertThat(first.status).isEqualTo(JobStatus.PENDING)
        assertThat(first.claimed().failed(policy, now, invalid).status).isEqualTo(JobStatus.FAILED)
        assertThat(policy.manualRetryAt(3, now)).isEqualTo(now.plus(Duration.ofMinutes(1)))
        assertThat(policy.manualRetryAt(5, now)).isNull()
    }
}
