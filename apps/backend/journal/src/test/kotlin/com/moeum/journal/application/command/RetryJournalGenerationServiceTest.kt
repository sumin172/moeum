package com.moeum.journal.application.command

import com.moeum.journal.domain.JournalGenerationNotRetryableException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.JournalId
import com.moeum.journal.support.InMemoryGenerationJobRepository
import com.moeum.journal.support.MutableTimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RetryJournalGenerationServiceTest {

    private val userId = UserId.generate()
    private val day = LocalDate.of(2026, 8, 2)
    private val now = Instant.parse("2026-08-03T05:00:00Z")
    private val jobs = InMemoryGenerationJobRepository()
    private val service = RetryJournalGenerationService(jobs, MutableTimeProvider(now))

    private fun job(transform: (GenerationJob) -> GenerationJob) =
        jobs.save(transform(GenerationJob.pending(userId, day, now.minusSeconds(3600), now.minusSeconds(7200))))

    @Test
    fun `재시도를 소진해 실패한 생성을 다시 시작한다`() {
        job { it.failedPermanently("LlmException", now) }

        val restarted = service.retry(userId, day)

        assertThat(restarted.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(restarted.state.attemptCount).isZero()
        assertThat(restarted.state.nextAttemptAt).isEqualTo(now)
    }

    @Test
    fun `이미 생성된 일기는 거부하고, 생성할 일기가 없는 하루도 거부한다`() {
        job { it.completed(JournalId.generate(), "p", "m", "v", 1, 1, UUID.randomUUID(), now) }

        assertThatThrownBy { service.retry(userId, day) }.isInstanceOf(JournalGenerationNotRetryableException::class.java)
        assertThatThrownBy { service.retry(userId, day.minusDays(1)) }.isInstanceOf(JournalNotFoundException::class.java)
    }
}
