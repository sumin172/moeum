package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import java.time.ZoneId
import com.moeum.conversation.domain.model.ResponseFailureReason
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.support.*
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.llm.LlmException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.filter
import kotlin.collections.getValue
import kotlin.collections.listOf
import kotlin.collections.map
import kotlin.collections.mutableListOf
import kotlin.collections.plusAssign
import kotlin.collections.set
import kotlin.collections.single

class ResponseJobExecutorTest {

    private val userId = UserId.generate()
    private val recordDayId = RecordDayId.generate()
    private val start = Instant.parse("2026-08-08T10:00:00Z")
    private val timeProvider = FixedTimeProvider(start)
    private val messageRepository = InMemoryMessageRepository()
    private val jobRepository = InMemoryResponseJobRepository()
    private val records = InMemoryRecordDayRepository().also { it.insert(RecordDay.create(userId,start,ZoneId.of("Asia/Seoul"),RecordDay.DEFAULT_CLOSE_TIME,null).copy(id=recordDayId)) }

    private class ScriptedResponder(private val outcomes: ArrayDeque<() -> ConversationResponse>) : ConversationResponder {
        val requests = mutableListOf<List<Message>>()
        override fun respond(context: List<Message>): ConversationResponse {
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
            "v1",
        )
    }

    private val failure = { throw LlmException("LLM 호출 실패") }

    private fun executor(responder: ConversationResponder, repository: ResponseJobRepository = jobRepository) = ResponseJobExecutor(
        responseJobRepository = repository,
        messageRepository = messageRepository,
        conversationResponder = responder,
        saveGeneratedResponseService = SaveGeneratedResponseService(messageRepository, repository, timeProvider, records),
        markResponseFailedService = MarkResponseFailedService(repository, testResponseJobProperties, timeProvider),
        responseJobProperties = testResponseJobProperties,
        timeProvider = timeProvider,
        limits = ConversationLimitProperties(),
    )

    private fun enqueue(content: String = "오늘 정말 피곤했다", occurredAt: Instant = start): ResponseJob {
        val message = messageRepository.append(userMessage(userId, content, occurredAt, recordDayId, sourceSequence=messageRepository.messages.values.count { it.recordDayId==recordDayId && it.role==MessageRole.USER }.toLong()+1))
        return jobRepository.save(ResponseJob.pending(message, start))
    }

    private fun jobOf(job: ResponseJob) = jobRepository.jobs.getValue(job.id)
    private fun assistantMessages() = messageRepository.messages.values.filter { it.role == MessageRole.ASSISTANT }

    private fun ResponseJobExecutor.executeNext(): Boolean {
        val job = claim(1).singleOrNull() ?: return false
        execute(job)
        return true
    }

    @Test
    fun `빈 큐는 다음 poll까지 반복 조회하지 않는다`() {
        val probes = AtomicInteger()
        val repository = object : ResponseJobRepository by jobRepository {
            override fun claimBatch(now: Instant, leaseExpiresAt: Instant, limit: Int): List<ResponseJob> {
                probes.incrementAndGet()
                return jobRepository.claimBatch(now, leaseExpiresAt, limit)
            }
        }
        val runner = executor(ScriptedResponder(ArrayDeque(listOf(answer()))), repository)
        repeat(32) { assertThat(runner.executeNext()).isFalse() }
        assertThat(probes.get()).isEqualTo(1)
        enqueue()
        assertThat(runner.canPoll()).isFalse()
        timeProvider.fixedNow = start.plusMillis(500)
        assertThat(runner.canPoll()).isTrue()
        assertThat(runner.executeNext()).isTrue()
        assertThat(assistantMessages()).hasSize(1)
    }

    @Test
    fun `성공하면 assistant 메시지를 저장하고 작업을 완료한다`() {
        val job = enqueue()

        executor(ScriptedResponder(ArrayDeque(listOf(answer())))).executeNext()

        assertThat(jobOf(job).state.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(assistantMessages().single().content).isEqualTo("많이 힘드셨겠어요.")
        assertThat(assistantMessages().single().recordDayId).isEqualTo(recordDayId)
    }

    @Test
    fun `자정을 넘겨도 같은 하루의 대화만 컨텍스트로 전달한다`() {
        messageRepository.append(userMessage(userId, "자정 전", Instant.parse("2026-08-08T14:50:00Z"), recordDayId))
        messageRepository.append(userMessage(userId, "전날", Instant.parse("2026-08-07T10:00:00Z"), RecordDayId.generate()))
        messageRepository.append(userMessage(UserId.generate(), "다른 사용자", Instant.parse("2026-08-08T14:55:00Z"), recordDayId))
        enqueue("자정 후",Instant.parse("2026-08-08T15:01:00Z"))
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))

        executor(responder).executeNext()

        assertThat(responder.requests.single().map { it.content }).containsExactly("자정 전", "자정 후")
    }

    @Test
    fun `선행 자동 재시도는 후속 발화를 섞지 않고 완료 후 다음 응답에 선행 답변을 전달한다`() {
        enqueue("첫 번째")
        enqueue("두 번째",start.plusSeconds(1))
        val responder = ScriptedResponder(ArrayDeque(listOf(failure,answer("첫 응답"),answer("다음 응답"))))
        val runner = executor(responder)
        assertThat(runner.executeNext()).isTrue()
        assertThat(runner.executeNext()).isFalse()
        timeProvider.fixedNow = start.plusSeconds(120)
        assertThat(runner.executeNext()).isTrue()
        assertThat(responder.requests.take(2).map { it.map { message -> message.content } })
            .containsExactly(listOf("첫 번째"),listOf("첫 번째"))
        assertThat(runner.executeNext()).isTrue()
        assertThat(responder.requests.last().map { it.content }).containsExactly("첫 번째","첫 응답","두 번째")
    }

    @Test
    fun `실패하면 backoff 뒤로 재시도를 예약하고 assistant 메시지는 저장하지 않는다`() {
        val job = enqueue()

        executor(ScriptedResponder(ArrayDeque(listOf(failure)))).executeNext()

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

        executor.executeNext()
        repeat(2) {
            timeProvider.fixedNow = timeProvider.fixedNow.plus(Duration.ofMinutes(5))
            executor.executeNext()
        }

        val failed = jobOf(job)
        assertThat(failed.state.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.state.attemptCount).isEqualTo(3)
        assertThat(failed.failureReason).isEqualTo(ResponseFailureReason.GENERATION_FAILED)
        assertThat(failed.retryable).isTrue()
        assertThat(executor.executeNext()).isFalse()
    }

    @Test
    fun `같은 작업을 두 번 실행해도 LLM은 한 번만 불린다`() {
        enqueue()
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))
        val executor = executor(responder)

        executor.executeNext()
        executor.executeNext()

        assertThat(responder.requests).hasSize(1)
        assertThat(assistantMessages()).hasSize(1)
    }

    @Test
    fun `리스를 뺏긴 워커의 늦은 결과는 버려지고 assistant 메시지가 중복 저장되지 않는다`() {
        val job = enqueue()
        // LLM 호출이 리스(2분)보다 오래 걸리는 사이 다른 워커가 같은 작업을 다시 잡는 상황
        val slowThenReclaimed = {
            timeProvider.fixedNow = start.plus(Duration.ofMinutes(3))
            jobRepository.claimBatch(timeProvider.fixedNow, timeProvider.fixedNow.plus(Duration.ofMinutes(2)), 1)
            answer("늦은 응답").invoke()
        }

        executor(ScriptedResponder(ArrayDeque(listOf(slowThenReclaimed)))).executeNext()

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

        executor.executeNext()

        assertThat(responder.requests).isEmpty()
        assertThat(jobOf(job).state.status).isEqualTo(JobStatus.FAILED)
        assertThat(jobOf(job).state.lastErrorCode).isEqualTo("ATTEMPTS_EXHAUSTED")
    }
}
