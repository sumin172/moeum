package com.moeum.conversation.application.command

import com.moeum.conversation.domain.AiQuotaExceededException
import com.moeum.conversation.domain.MessageNotFoundException
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryAiUsageRepository
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.InMemoryResponseJobRepository
import com.moeum.conversation.support.quotaGuard
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class RetryResponseServiceTest {

    private val userId = UserId.generate()
    private val dayDate = LocalDate.of(2026, 8, 8)
    private val now = Instant.parse("2026-08-08T10:00:00Z")
    private val messageRepository = InMemoryMessageRepository()
    private val jobRepository = InMemoryResponseJobRepository()
    private val usage = InMemoryAiUsageRepository()

    private fun service(quotaLimit: Int = 20) =
        RetryResponseService(messageRepository, jobRepository, quotaGuard(quotaLimit, usage), FixedTimeProvider(now))

    private fun messageWithJob(transform: (ResponseJob) -> ResponseJob): ResponseJob {
        val message = messageRepository.save(userMessage(userId, "질문", now, dayDate))
        return jobRepository.save(transform(ResponseJob.pending(message, now)))
    }

    private fun failed(job: ResponseJob) = job.failedPermanently("ConversationResponseException", now)

    @Test
    fun `최종 실패한 응답을 다시 요청하면 바로 실행할 수 있는 상태로 되돌리고 quota를 1 쓴다`() {
        val job = messageWithJob(::failed)

        val result = service().retry(userId, job.userMessageId)

        assertThat(result.restarted).isTrue()
        assertThat(result.responseJob.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(result.responseJob.state.nextAttemptAt).isEqualTo(now)
        assertThat(result.responseJob.failureReason).isNull()
        assertThat(usage.counts[userId to dayDate]).isEqualTo(1)
    }

    @Test
    fun `대기 중인 응답을 다시 요청하면 아무것도 바꾸지 않고 현재 상태를 돌려준다`() {
        val job = messageWithJob { it }

        val result = service().retry(userId, job.userMessageId)

        assertThat(result.restarted).isFalse()
        assertThat(result.responseJob).isEqualTo(job)
        assertThat(usage.counts).isEmpty()
    }

    @Test
    fun `이미 응답이 생성된 메시지는 거부한다`() {
        val job = messageWithJob { it.completed(now) }

        assertThatThrownBy { service().retry(userId, job.userMessageId) }
            .isInstanceOf(ResponseAlreadyCompletedException::class.java)
    }

    @Test
    fun `하루 quota를 넘겼으면 거부한다`() {
        val job = messageWithJob(::failed)

        assertThatThrownBy { service(quotaLimit = 0).retry(userId, job.userMessageId) }
            .isInstanceOf(AiQuotaExceededException::class.java)
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.FAILED)
    }

    @Test
    fun `다른 사용자의 메시지는 없는 메시지로 본다`() {
        val job = messageWithJob(::failed)

        assertThatThrownBy { service().retry(UserId.generate(), job.userMessageId) }
            .isInstanceOf(MessageNotFoundException::class.java)
    }
}
