package com.moeum.journal.application.command

import com.moeum.journal.domain.model.failedPermanently

import com.moeum.journal.domain.JournalGenerationNotRetryableException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.support.InMemoryGenerationJobRepository
import com.moeum.journal.support.MutableTimeProvider
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Duration

class RetryJournalGenerationServiceTest {

    private val userId = UserId.generate()
    private val day = RecordDayId.generate()
    private val now = Instant.parse("2026-08-03T05:00:00Z")
    private val jobs = InMemoryGenerationJobRepository()
    private val clock = MutableTimeProvider(now)
    private val journals = com.moeum.journal.support.InMemoryJournalRepository()
    private val query = com.moeum.journal.application.query.GetJournalService(journals, jobs,
        org.mockito.Mockito.mock(com.moeum.identity.application.publicapi.FeatureAccessQuery::class.java),
        clock, com.moeum.platform.job.JobRetentionProperties())
    private val service = RetryJournalGenerationService(jobs, clock, journals,
        com.moeum.platform.job.JobRetentionProperties())

    private fun job(transform: (GenerationJob) -> GenerationJob) =
        jobs.save(transform(com.moeum.journal.domain.model.TestGenerationJobs.pending(userId, day, now.minusSeconds(3600), now.minusSeconds(7200))))

    @Test
    fun `조회와 실행은 생성 시각의 30일 경계를 공유하고 재실패해도 연장되지 않는다`() {
        val failed = job { it.copy(createdAt = now.minus(Duration.ofDays(30)).plusSeconds(1),
            state = it.state.copy(attemptCount = 20)).failedPermanently(now) }
        assertThat(query.getDay(userId, day).generationRetryable).isTrue()
        val restarted = service.retry(userId, day)
        jobs.save(restarted.failedPermanently(now))
        clock.now = now.plusSeconds(1)
        assertThat(query.getDay(userId, day).generationRetryable).isFalse()
        assertThatThrownBy { service.retry(userId, day) }.isInstanceOf(JournalNotFoundException::class.java)
        assertThat(jobs.jobs.getValue(failed.id).createdAt).isEqualTo(failed.createdAt)
    }

    @Test
    fun `작업 생성 후 30일이면 최근 실패라도 재시도하지 않는다`() {
        job { it.copy(createdAt = now.minus(Duration.ofDays(30))).failedPermanently(now) }
        assertThatThrownBy { service.retry(userId, day) }.isInstanceOf(JournalNotFoundException::class.java)
    }

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
    fun `누적 상한을 넘겨도 실패 직후 바로 재개하며 생성 시각과 횟수를 유지한다`() {
        val failed = job { it.copy(state = it.state.copy(attemptCount = 20)).failedPermanently(now) }
        val restarted = service.retry(userId, day)
        assertThat(restarted.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(restarted.state.attemptCount).isEqualTo(20)
        assertThat(restarted.createdAt).isEqualTo(failed.createdAt)
        assertThat(service.retry(userId, day)).isEqualTo(restarted)
    }
}
