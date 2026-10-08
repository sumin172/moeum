package com.moeum.platform.job

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

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
        val first = JobState.pending(now).claimed().failed("E1", policy, now)
        assertThat(first.status).isEqualTo(JobStatus.PENDING)
        assertThat(first.nextAttemptAt).isEqualTo(now.plusSeconds(10))
        assertThat(first.lastErrorCode).isEqualTo("E1")

        val second = first.claimed().failed("E2", policy, now)
        assertThat(second.status).isEqualTo(JobStatus.PENDING)
        assertThat(second.nextAttemptAt).isEqualTo(now.plusSeconds(60))

        // backoff 목록보다 시도가 많아도 마지막 값을 쓰지만, 여기서는 3번째가 마지막 시도라 최종 실패
        val third = second.claimed().failed("E3", policy, now)
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
        assertThat(policy.isExhausted(4)).isTrue()
    }

    @Test
    fun `최종 실패한 작업만 사용자가 다시 시작할 수 있고, 자동 재시도 횟수를 새로 센다`() {
        val failed = JobState.pending(now).claimed().failedPermanently("E")
        val restarted = failed.restarted(now.plusSeconds(100))

        assertThat(restarted.status).isEqualTo(JobStatus.PENDING)
        assertThat(restarted.attemptCount).isZero()
        assertThat(restarted.nextAttemptAt).isEqualTo(now.plusSeconds(100))
        assertThat(restarted.lastErrorCode).isNull()

        assertThatThrownBy { JobState.pending(now).restarted(now) }.isInstanceOf(IllegalStateException::class.java)
    }
}
