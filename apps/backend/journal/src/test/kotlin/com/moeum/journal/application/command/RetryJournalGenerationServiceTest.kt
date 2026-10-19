package com.moeum.journal.application.command

import com.moeum.journal.domain.JournalGenerationNotRetryableException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.support.InMemoryGenerationJobRepository
import com.moeum.journal.support.MutableTimeProvider
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.job.ManualRetryUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class RetryJournalGenerationServiceTest {

    private val userId = UserId.generate()
    private val day = RecordDayId.generate()
    private val now = Instant.parse("2026-08-03T05:00:00Z")
    private val jobs = InMemoryGenerationJobRepository()
    private val service = RetryJournalGenerationService(jobs, MutableTimeProvider(now),
        com.moeum.journal.support.InMemoryJournalRepository(),
        com.moeum.journal.infrastructure.config.GenerationJobProperties(manualRetryCooldown = java.time.Duration.ZERO))

    private fun job(transform: (GenerationJob) -> GenerationJob) =
        jobs.save(transform(com.moeum.journal.domain.model.TestGenerationJobs.pending(userId, day, now.minusSeconds(3600), now.minusSeconds(7200))))

    @Test
    fun `재시도를 소진해 실패한 생성을 다시 시작한다`() {
        job { it.failedPermanently(now) }

        val restarted = service.retry(userId, day)

        assertThat(restarted.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(restarted.state.attemptCount).isZero()
        assertThat(restarted.state.nextAttemptAt).isEqualTo(now)
    }

    @Test
    fun `이미 생성된 일기는 거부하고, 생성할 일기가 없는 하루도 거부한다`() {
        job { it.completed(now) }

        assertThatThrownBy { service.retry(userId, day) }.isInstanceOf(JournalGenerationNotRetryableException::class.java)
        assertThatThrownBy { service.retry(userId, RecordDayId.generate()) }.isInstanceOf(JournalNotFoundException::class.java)
    }

    @Test
    fun `수동 재시도는 실패 시각 뒤 대기하고 누적 상한에서 멈춘다`() {
        val failed = job { it.copy(state = it.state.copy(attemptCount = 2)).failedPermanently(now) }
        val limited = RetryJournalGenerationService(jobs, MutableTimeProvider(now),
            com.moeum.journal.support.InMemoryJournalRepository(),
            com.moeum.journal.infrastructure.config.GenerationJobProperties(maxAttempts = 2))
        assertThatThrownBy { limited.retry(userId, day) }.isInstanceOf(ManualRetryUnavailableException::class.java)
        jobs.jobs[failed.id] = failed.copy(state = failed.state.copy(attemptCount = 4))
        assertThatThrownBy { limited.retry(userId, day) }.isInstanceOf(ManualRetryUnavailableException::class.java)
    }
}
