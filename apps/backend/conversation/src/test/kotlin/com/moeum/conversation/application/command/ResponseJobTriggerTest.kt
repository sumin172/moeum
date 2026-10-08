package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.InMemoryResponseJobRepository
import com.moeum.conversation.support.testResponseJobProperties
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.job.WorkerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ResponseJobTriggerTest {

    private val now = Instant.parse("2026-08-08T10:00:00Z")
    private val timeProvider = FixedTimeProvider(now)
    private val messageRepository = InMemoryMessageRepository()
    private val jobRepository = InMemoryResponseJobRepository()
    private var llmCalls = 0
    private val responder = object : ConversationResponder {
        override fun respond(userId: UserId, context: List<Message>): ConversationResponse {
            llmCalls++
            return ConversationResponse(UUID.randomUUID(), "답변", "m", "p", "v", 1, 1)
        }
    }
    private val executor = ResponseJobExecutor(
        jobRepository, messageRepository, responder,
        SaveGeneratedResponseService(messageRepository, jobRepository, timeProvider),
        MarkResponseFailedService(jobRepository, testResponseJobProperties, timeProvider),
        testResponseJobProperties, timeProvider,
    )

    private fun enqueue(): ResponseJob {
        val message = messageRepository.append(userMessage(UserId.generate(), "질문", now, LocalDate.of(2026, 8, 8)))
        return jobRepository.save(ResponseJob.pending(message, now))
    }

    @Test
    fun `워커 역할이면 요청 직후 바로 실행한다`() {
        val job = enqueue()

        ResponseJobTrigger(executor, WorkerProperties(enabled = true)).requestImmediateExecution(job.id)

        assertThat(llmCalls).isEqualTo(1)
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `API 전용 인스턴스면 실행하지 않고 작업을 워커 poller에 남긴다`() {
        val job = enqueue()

        ResponseJobTrigger(executor, WorkerProperties(enabled = false)).requestImmediateExecution(job.id)

        assertThat(llmCalls).isZero()
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.PENDING)
    }
}
