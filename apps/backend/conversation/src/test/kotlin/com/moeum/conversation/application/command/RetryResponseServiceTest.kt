package com.moeum.conversation.application.command

import com.moeum.conversation.domain.RecordDayNotFoundException
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.conversation.domain.ResponseJobNotFoundException
import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.conversation.support.*
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobRetentionProperties
import com.moeum.platform.job.JobStatus
import com.moeum.platform.job.ManualRetryUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class RetryResponseServiceTest {

    private val userId = UserId.generate()
    private val recordDayId = RecordDayId.generate()
    private val now = Instant.parse("2026-08-08T10:00:00Z")
    private val records = InMemoryRecordDayRepository().also {
        it.insert(RecordDay.create(userId,now,ZoneId.of("Asia/Seoul"),RecordDay.DEFAULT_CLOSE_TIME,LocalTime.of(18,0),LocalTime.of(4,0)).copy(id=recordDayId))
    }
    private val jobRepository = InMemoryResponseJobRepository(records)

    private fun service(properties: ResponseJobProperties = ResponseJobProperties()) =
        RetryResponseService(jobRepository, records, FixedTimeProvider(now), properties, JobRetentionProperties())

    private fun job(transform: (ResponseJob) -> ResponseJob): ResponseJob {
        val job = transform(ResponseJob.pending(userId, recordDayId, now).copy(observedSourceRevision = 2))
        jobRepository.insert(job)
        return job
    }

    // 실패 시각과 저장한 재개 가능 시각을 지정한다.
    private fun failed(failedAt: Instant = now.minusSeconds(120), reopenAt: Instant = failedAt.plusSeconds(60), attempts: Int = 3) =
        { it: ResponseJob -> it.copy(state = it.state.copy(attemptCount = attempts).failedUntil(reopenAt), updatedAt = failedAt) }

    @Test
    fun `최종 실패 후 30일이 지나면 정리 전이라도 재시도하지 않는다`() {
        job(failed(failedAt = now.minus(Duration.ofDays(30))))
        assertThatThrownBy { service().retry(userId, recordDayId) }.isInstanceOf(ResponseJobNotFoundException::class.java)
    }

    @Test
    fun `최종 실패한 응답을 재요청하면 같은 범위·시도 횟수로 즉시 실행 가능한 대기 상태로 되돌린다`() {
        job(failed())

        val result = service().retry(userId, recordDayId)

        assertThat(result.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(result.state.nextAttemptAt).isEqualTo(now)
        assertThat(result.state.attemptCount).isEqualTo(3)
        assertThat(result.observedSourceRevision).isEqualTo(2)
    }

    @Test
    fun `저장된 재개 가능 시각 전에는 공급자 대기를 우회하지 않는다`() {
        val reopenAt = now.plus(Duration.ofMinutes(3))
        job(failed(failedAt = now.minusSeconds(120), reopenAt = reopenAt))

        assertThatThrownBy { service().retry(userId, recordDayId) }
            .isInstanceOfSatisfying(ManualRetryUnavailableException::class.java) { assertThat(it.nextAllowedAt).isEqualTo(reopenAt) }
        assertThat(jobRepository.jobs.getValue(recordDayId).state.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `누적 시도 상한에 도달하면 수동 재시도를 중단한다`() {
        job(failed(attempts = 5))
        assertThatThrownBy { service().retry(userId, recordDayId) }
            .isInstanceOfSatisfying(ManualRetryUnavailableException::class.java) { assertThat(it.nextAllowedAt).isNull() }
    }

    @Test
    fun `대기 중인 응답을 다시 요청하면 아무것도 바꾸지 않고 현재 상태를 돌려준다`() {
        val job = job { it }
        assertThat(service().retry(userId, recordDayId)).isEqualTo(job)
    }

    @Test
    fun `재시도할 실패 응답이 없는 기록은 거부한다`() {
        job { it.copy(state = it.state.completed()) }
        assertThatThrownBy { service().retry(userId, recordDayId) }.isInstanceOf(ResponseAlreadyCompletedException::class.java)
    }

    @Test
    fun `다른 사용자의 기록과 응답 작업이 없는 기록은 찾지 못한다`() {
        assertThatThrownBy { service().retry(userId, recordDayId) }.isInstanceOf(ResponseJobNotFoundException::class.java)
        job(failed())
        assertThatThrownBy { service().retry(UserId.generate(), recordDayId) }.isInstanceOf(RecordDayNotFoundException::class.java)
    }
}
