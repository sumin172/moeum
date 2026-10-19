package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ResponseSupersededException
import com.moeum.conversation.domain.model.RecordDay
import java.time.ZoneId
import com.moeum.conversation.domain.MessageNotFoundException
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.support.*
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class RetryResponseServiceTest {

    private val userId = UserId.generate()
    private val recordDayId = RecordDayId.generate()
    private val now = Instant.parse("2026-08-08T10:00:00Z")
    private val messageRepository = InMemoryMessageRepository()
    private val jobRepository = InMemoryResponseJobRepository()

    private val records = InMemoryRecordDayRepository().also {
        it.insert(RecordDay.create(userId,now,ZoneId.of("Asia/Seoul"),RecordDay.DEFAULT_CLOSE_TIME,null).copy(id=recordDayId))
    }
    private fun service() = RetryResponseService(messageRepository, jobRepository, records, FixedTimeProvider(now))

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
        assertThatThrownBy { service().retry(userId,job.userMessageId) }.isInstanceOf(ResponseSupersededException::class.java)
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `다른 사용자의 메시지는 없는 메시지로 본다`() {
        val job = messageWithJob(::failed)

        assertThatThrownBy { service().retry(UserId.generate(), job.userMessageId) }
            .isInstanceOf(MessageNotFoundException::class.java)
    }
}
