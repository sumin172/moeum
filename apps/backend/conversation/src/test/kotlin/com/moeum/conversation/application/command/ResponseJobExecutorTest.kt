package com.moeum.conversation.application.command

import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.domain.model.ResponseFailureReason
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.InMemoryResponseJobRepository
import com.moeum.conversation.support.testResponseJobProperties
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.model.Message
import com.moeum.platform.llm.LlmException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ResponseJobExecutorTest {

    private val userId = UserId.generate()
    private val dayDate = LocalDate.of(2026, 8, 8)
    private val start = Instant.parse("2026-08-08T10:00:00Z")
    private val timeProvider = FixedTimeProvider(start)
    private val messageRepository = InMemoryMessageRepository()
    private val jobRepository = InMemoryResponseJobRepository()

    private class ScriptedResponder(private val outcomes: ArrayDeque<() -> ConversationResponse>) : ConversationResponder {
        val requests = mutableListOf<List<Message>>()
        override fun respond(userId: UserId, context: List<Message>): ConversationResponse {
            requests += context
            return outcomes.removeFirst().invoke()
        }
    }

    private fun answer(
        content: String = "많이 힘드셨겠어요."
    ): () -> ConversationResponse = {
        ConversationResponse(
            UUID.randomUUID(),
            content,
            "gemini-flash-latest",
            "google",
            "v1",
            10,
            5,
        )
    }

    private val failure = { throw LlmException("LLM 호출 실패") }

    private fun executor(responder: ConversationResponder) = ResponseJobExecutor(
        responseJobRepository = jobRepository,
        messageRepository = messageRepository,
        conversationResponder = responder,
        saveGeneratedResponseService = SaveGeneratedResponseService(messageRepository, jobRepository, timeProvider),
        markResponseFailedService = MarkResponseFailedService(jobRepository, testResponseJobProperties, timeProvider),
        responseJobProperties = testResponseJobProperties,
        timeProvider = timeProvider,
    )

    private fun enqueue(content: String = "오늘 정말 피곤했다", occurredAt: Instant = start): ResponseJob {
        val message = messageRepository.save(userMessage(userId, content, occurredAt, dayDate))
        return jobRepository.save(ResponseJob.pending(message, start))
    }

    private fun jobOf(job: ResponseJob) = jobRepository.jobs.getValue(job.id)
    private fun assistantMessages() = messageRepository.messages.values.filter { it.role == MessageRole.ASSISTANT }

    @Test
    fun `성공하면 assistant 메시지를 저장하고 작업을 완료한다`() {
        val job = enqueue()

        executor(ScriptedResponder(ArrayDeque(listOf(answer())))).executeAsync(job.id)

        assertThat(jobOf(job).state.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(assistantMessages().single().content).isEqualTo("많이 힘드셨겠어요.")
        assertThat(assistantMessages().single().dayDate).isEqualTo(dayDate)
    }

    @Test
    fun `자정을 넘겨도 같은 하루의 대화만 컨텍스트로 전달한다`() {
        messageRepository.save(userMessage(userId, "자정 전", Instant.parse("2026-08-08T14:50:00Z"), dayDate))
        messageRepository.save(userMessage(userId, "전날", Instant.parse("2026-08-07T10:00:00Z"), dayDate.minusDays(1)))
        messageRepository.save(userMessage(UserId.generate(), "다른 사용자", Instant.parse("2026-08-08T14:55:00Z"), dayDate))
        val job = enqueue("자정 후", Instant.parse("2026-08-08T15:10:00Z"))
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))

        executor(responder).executeAsync(job.id)

        assertThat(responder.requests.single().map { it.content }).containsExactly("자정 전", "자정 후")
    }

    @Test
    fun `실패하면 backoff 뒤로 재시도를 예약하고 assistant 메시지는 저장하지 않는다`() {
        val job = enqueue()

        executor(ScriptedResponder(ArrayDeque(listOf(failure)))).executeAsync(job.id)

        val state = jobOf(job).state
        assertThat(state.status).isEqualTo(JobStatus.PENDING)
        assertThat(state.attemptCount).isEqualTo(1)
        // 첫 backoff 10초 + 지터 최대 20%
        assertThat(state.nextAttemptAt).isBetween(start.plusSeconds(10), start.plusSeconds(12))
        assertThat(state.lastErrorCode).isEqualTo("LlmException")
        assertThat(assistantMessages()).isEmpty()
    }

    @Test
    fun `poller가 재시도 차례가 된 작업을 다시 실행하고, 재시도를 모두 실패하면 사용자 재시도 가능한 최종 실패로 끝난다`() {
        val job = enqueue()
        val executor = executor(ScriptedResponder(ArrayDeque(listOf(failure, failure, failure))))

        executor.executeAsync(job.id)
        repeat(2) {
            timeProvider.fixedNow = timeProvider.fixedNow.plus(Duration.ofMinutes(5))
            executor.executeClaimedAsync(executor.claimNext()!!)
        }

        val failed = jobOf(job)
        assertThat(failed.state.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.state.attemptCount).isEqualTo(3)
        assertThat(failed.failureReason).isEqualTo(ResponseFailureReason.GENERATION_FAILED)
        assertThat(failed.retryable).isTrue()
        assertThat(executor.claimNext()).isNull()
    }

    @Test
    fun `같은 작업을 두 번 실행해도 LLM은 한 번만 불린다`() {
        val job = enqueue()
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))
        val executor = executor(responder)

        executor.executeAsync(job.id)
        executor.executeAsync(job.id)

        assertThat(responder.requests).hasSize(1)
        assertThat(assistantMessages()).hasSize(1)
    }

    @Test
    fun `리스를 뺏긴 워커의 늦은 결과는 버려지고 assistant 메시지가 중복 저장되지 않는다`() {
        val job = enqueue()
        // LLM 호출이 리스(2분)보다 오래 걸리는 사이 다른 워커가 같은 작업을 다시 잡는 상황
        val slowThenReclaimed = {
            timeProvider.fixedNow = start.plus(Duration.ofMinutes(3))
            jobRepository.claimNext(timeProvider.fixedNow, timeProvider.fixedNow.plus(Duration.ofMinutes(2)))
            answer("늦은 응답").invoke()
        }

        executor(ScriptedResponder(ArrayDeque(listOf(slowThenReclaimed)))).executeAsync(job.id)

        assertThat(assistantMessages()).isEmpty()
        // 다른 워커가 잡은 상태 그대로 남는다
        assertThat(jobOf(job).state.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(jobOf(job).state.attemptCount).isEqualTo(2)
    }

    @Test
    fun `리스 만료로 다시 잡혀 시도 횟수를 넘긴 작업은 실행하지 않고 최종 실패시킨다`() {
        val job = enqueue()
        jobRepository.jobs[job.id] = job.copy(
            state = job.state.copy(status = JobStatus.PROCESSING, attemptCount = 3, leaseExpiresAt = start.minusSeconds(1)),
        )
        val responder = ScriptedResponder(ArrayDeque())
        val executor = executor(responder)

        executor.executeClaimedAsync(executor.claimNext()!!)

        assertThat(responder.requests).isEmpty()
        assertThat(jobOf(job).state.status).isEqualTo(JobStatus.FAILED)
        assertThat(jobOf(job).state.lastErrorCode).isEqualTo("ATTEMPTS_EXHAUSTED")
    }
}
