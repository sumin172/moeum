package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ResponseSupersededException
import com.moeum.conversation.domain.model.RecordDay
import java.time.ZoneId
import com.moeum.conversation.domain.MessageNotFoundException
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.support.*
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.job.ManualRetryUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class RetryResponseServiceTest {

    private val userId = UserId.generate()
    private val recordDayId = RecordDayId.generate()
    private val now = Instant.parse("2026-08-08T10:00:00Z")
    private val messageRepository = InMemoryMessageRepository()
    private val jobRepository = InMemoryResponseJobRepository(messageRepository)

    private val records = InMemoryRecordDayRepository().also {
        it.insert(RecordDay.create(userId,now,ZoneId.of("Asia/Seoul"),RecordDay.DEFAULT_CLOSE_TIME,null).copy(id=recordDayId))
    }
    private fun service() = RetryResponseService(messageRepository, jobRepository, records, FixedTimeProvider(now),
        com.moeum.conversation.infrastructure.config.ResponseJobProperties(manualRetryCooldown = java.time.Duration.ZERO))

    private fun messageWithJob(transform: (ResponseJob) -> ResponseJob): ResponseJob {
        val message = messageRepository.append(userMessage(userId, "질문", now, recordDayId))
        return jobRepository.save(transform(ResponseJob.pending(message, now)))
    }

    private fun failed(job: ResponseJob) = job.failedPermanently(now)

    @Test
    fun `최종 실패한 응답을 재요청하면 즉시 실행 가능한 대기 상태로 되돌린다`() {
        val job = messageWithJob(::failed)

        val result = service().retry(userId, job.userMessageId)

        assertThat(result.responseJob.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(result.responseJob.state.nextAttemptAt).isEqualTo(now)
        assertThat(result.responseJob.failureReason).isNull()
    }

    @Test
    fun `대기 중인 응답을 다시 요청하면 아무것도 바꾸지 않고 현재 상태를 돌려준다`() {
        val job = messageWithJob { it }

        val result = service().retry(userId, job.userMessageId)

        assertThat(result.responseJob).isEqualTo(job)
    }

    @Test
    fun `이미 응답이 생성된 메시지는 거부한다`() {
        val job = messageWithJob { it.completed(now) }

        assertThatThrownBy { service().retry(userId, job.userMessageId) }
            .isInstanceOf(ResponseAlreadyCompletedException::class.java)
    }

    @Test
    fun `후속 응답이 시작되면 과거 실패 응답의 재요청을 거절한다`() {
        val job = messageWithJob(::failed)
        val laterMessage = messageRepository.append(userMessage(userId,"다음 질문",now,recordDayId,sourceSequence=2))
        jobRepository.save(ResponseJob.pending(laterMessage,now).completed(now))
        messageRepository.append(Message.assistantMessage(MessageId.generate(),userId,"다음 답변",now,recordDayId,2))
        assertThatThrownBy { service().retry(userId,job.userMessageId) }.isInstanceOf(ResponseSupersededException::class.java)
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `후속 응답이 대기 중일 뿐이면 선행 실패 응답을 재시도할 수 있다`() {
        val job = messageWithJob(::failed)
        val laterMessage = messageRepository.append(userMessage(userId,"다음 질문",now,recordDayId,sourceSequence=2))
        jobRepository.save(ResponseJob.pending(laterMessage,now))

        assertThat(service().retry(userId,job.userMessageId).responseJob.state.status).isEqualTo(JobStatus.PENDING)
    }

    @Test
    fun `다른 사용자의 메시지는 없는 메시지로 본다`() {
        val job = messageWithJob(::failed)

        assertThatThrownBy { service().retry(UserId.generate(), job.userMessageId) }
            .isInstanceOf(MessageNotFoundException::class.java)
    }

    @Test
    fun `실패 직후 수동 재시도는 기다리고 누적 시도 상한에 도달하면 중단한다`() {
        val job = messageWithJob { it.copy(state = it.state.copy(attemptCount = 3)).failedPermanently(now) }
        val limited = RetryResponseService(messageRepository, jobRepository, records, FixedTimeProvider(now),
            com.moeum.conversation.infrastructure.config.ResponseJobProperties())
        assertThatThrownBy { limited.retry(userId, job.userMessageId) }
            .isInstanceOf(ManualRetryUnavailableException::class.java)
        jobRepository.jobs[job.id] = job.copy(state = job.state.copy(attemptCount = 5))
        assertThatThrownBy { service().retry(userId, job.userMessageId) }
            .isInstanceOf(ManualRetryUnavailableException::class.java)
    }
}
