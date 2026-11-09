package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.*
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.conversation.support.*
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.llm.LlmException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

class ResponseJobExecutorTest {

    private val userId = UserId.generate()
    private val recordDayId = RecordDayId.generate()
    private val start = Instant.parse("2026-08-08T10:00:00Z")
    private val timeProvider = FixedTimeProvider(start)
    private val messageRepository = InMemoryMessageRepository()
    private val records = InMemoryRecordDayRepository().also { it.insert(RecordDay.create(userId,start,ZoneId.of("Asia/Seoul"),RecordDay.DEFAULT_CLOSE_TIME,LocalTime.of(18,0),LocalTime.of(4,0)).copy(id=recordDayId)) }
    private val jobRepository = InMemoryResponseJobRepository(records)
    private val policy = testResponseJobProperties.toPolicy()

    private class ScriptedResponder(private val outcomes: ArrayDeque<() -> ConversationResponse>) : ConversationResponder {
        val requests = mutableListOf<Pair<List<Message>, List<Message>>>()
        override fun respond(history: List<Message>, targets: List<Message>): ConversationResponse {
            requests += history to targets
            return outcomes.removeFirst().invoke()
        }
    }

    private fun answer(content: String = "많이 힘드셨겠어요."): () -> ConversationResponse = { ConversationResponse(content) }

    private val failure = { throw LlmException("LLM 호출 실패") }
    private val unauthorized = { throw LlmException("sanitized", errorCode = "HTTP_401") }

    private fun executor(responder: ConversationResponder, repository: ResponseJobRepository = jobRepository,
                         limits: ConversationLimitProperties = ConversationLimitProperties()) = ResponseJobExecutor(
        responseJobRepository = repository,
        messageRepository = messageRepository,
        conversationResponder = responder,
        saveGeneratedResponseService = SaveGeneratedResponseService(messageRepository, repository, timeProvider, records),
        markResponseFailedService = MarkResponseFailedService(repository, records, testResponseJobProperties, timeProvider),
        timeProvider = timeProvider,
        limits = limits,
    )

    // 접수 서비스와 같은 순서로 원본 순번을 올리고 메시지·응답 상태를 갱신한다.
    private fun accept(content: String = "오늘 정말 피곤했다", occurredAt: Instant = timeProvider.fixedNow): Message {
        val record = records.find(userId, recordDayId)!!.append().also(records::update)
        val message = messageRepository.append(userMessage(userId, content, occurredAt, recordDayId, sourceSequence = record.sourceRevision))
        val now = timeProvider.fixedNow
        val current = jobRepository.findByRecord(userId, recordDayId)
        if (current == null) jobRepository.insert(ResponseJob.pending(userId, recordDayId, now))
        else current.inputAccepted(now)?.let(jobRepository::save)
        return message
    }

    private fun job() = jobRepository.jobs.getValue(recordDayId)
    private fun assistantMessages() = messageRepository.messages.values.filter { it.role == MessageRole.ASSISTANT }.sortedBy { it.streamSequence }
    private fun advance(duration: Duration) { timeProvider.fixedNow = timeProvider.fixedNow.plus(duration) }

    private fun ResponseJobExecutor.executeNext(): Boolean {
        val claimed = claim(1).singleOrNull() ?: return false
        execute(claimed)
        return true
    }

    @Test
    fun `성공하면 답한 마지막 원본 순번으로 assistant 메시지를 저장하고 완료한다`() {
        accept()

        executor(ScriptedResponder(ArrayDeque(listOf(answer())))).executeNext()

        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
        val assistant = assistantMessages().single()
        assertThat(assistant.content).isEqualTo("많이 힘드셨겠어요.")
        assertThat(assistant.sourceSequence).isEqualTo(1)
        assertThat(messageRepository.answeredSourceRevision(userId, recordDayId)).isEqualTo(1)
    }

    @Test
    fun `선점 전에 쌓인 연속 입력은 한 번의 호출로 함께 답하고 다른 기록·사용자 메시지는 섞지 않는다`() {
        messageRepository.append(userMessage(userId, "전날", Instant.parse("2026-08-07T10:00:00Z"), RecordDayId.generate()))
        messageRepository.append(userMessage(UserId.generate(), "다른 사용자", start, recordDayId, sourceSequence = 99))
        accept("자정 전", Instant.parse("2026-08-08T14:50:00Z"))
        accept("자정 후", Instant.parse("2026-08-08T15:01:00Z"))
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))

        executor(responder).executeNext()

        assertThat(responder.requests.single().first).isEmpty()
        assertThat(responder.requests.single().second.map { it.content }).containsExactly("자정 전", "자정 후")
        assertThat(assistantMessages().single().sourceSequence).isEqualTo(2)
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `실행 중 접수한 입력은 성공 후 새 실행 주기로 이어서 답하고 앞선 답변을 과거 문맥으로 전달한다`() {
        accept("첫 번째")
        val responder = ScriptedResponder(ArrayDeque(listOf({ accept("두 번째"); answer("첫 응답").invoke() }, answer("다음 응답"))))
        val runner = executor(responder)

        runner.executeNext()
        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job().state.attemptCount).isZero()
        runner.executeNext()

        assertThat(job().observedSourceRevision).isEqualTo(2)
        assertThat(responder.requests.last().first.map { it.content }).containsExactly("첫 번째", "첫 응답")
        assertThat(responder.requests.last().second.map { it.content }).containsExactly("두 번째")
        assertThat(assistantMessages().map { it.sourceSequence }).containsExactly(1L, 2L)
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `재시도 대기 중 접수한 입력은 예약 시각을 지키며 새 실행 주기로 함께 재시도한다`() {
        accept("첫 번째")
        val responder = ScriptedResponder(ArrayDeque(listOf(failure, answer("함께 답함"))))
        val runner = executor(responder)
        runner.executeNext()
        val retryAt = job().state.nextAttemptAt
        accept("두 번째")
        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job().state.attemptCount).isZero()
        assertThat(job().state.nextAttemptAt).isEqualTo(retryAt)
        assertThat(runner.executeNext()).isFalse()

        timeProvider.fixedNow = retryAt
        runner.executeNext()
        assertThat(responder.requests[1].second.map { it.content }).containsExactly("첫 번째", "두 번째")
        assertThat(job().observedSourceRevision).isEqualTo(2)
        assertThat(assistantMessages().map { it.sourceSequence }).containsExactly(2L)
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `실행 중 접수 뒤 실패하면 재시도가 남아 있어도 새 실행 주기로 함께 재시도한다`() {
        accept("첫 번째")
        val failsAfterIntake = { accept("두 번째"); throw LlmException("LLM 호출 실패") }
        val responder = ScriptedResponder(ArrayDeque(listOf(failsAfterIntake, answer("함께 답함"))))
        val runner = executor(responder)
        runner.executeNext()
        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job().state.attemptCount).isZero()
        // 일반 backoff 10초 + 지터 최대 20%
        assertThat(job().state.nextAttemptAt).isBetween(start.plusSeconds(10), start.plusSeconds(12))

        advance(Duration.ofSeconds(15))
        runner.executeNext()
        assertThat(responder.requests[1].second.map { it.content }).containsExactly("첫 번째", "두 번째")
        assertThat(assistantMessages().map { it.sourceSequence }).containsExactly(2L)
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `재시도 대기 중 입력이 이어져도 실행 기회는 입력마다만 다시 열리고 호출 수는 여유 상한 안이다`() {
        val inputs = 5
        val calls = AtomicInteger()
        val alwaysFailing = object : ConversationResponder {
            override fun respond(history: List<Message>, targets: List<Message>): ConversationResponse {
                calls.incrementAndGet()
                throw LlmException("LLM 호출 실패")
            }
        }
        val runner = executor(alwaysFailing)
        repeat(inputs) { index ->
            accept("입력 $index")
            advance(Duration.ofMinutes(10))
            runner.executeNext()
            assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        }
        repeat(50) { advance(Duration.ofMinutes(10)); runner.executeNext() }

        assertThat(job().state.status).isEqualTo(JobStatus.FAILED)
        assertThat(calls.get()).isEqualTo(inputs - 1 + testResponseJobProperties.maxAttempts)
        assertThat(calls.get()).isLessThanOrEqualTo((2 * inputs + 1) * testResponseJobProperties.maxAttempts)
    }

    @Test
    fun `실패하면 backoff 뒤로 재시도를 예약하고 assistant 메시지는 저장하지 않는다`() {
        accept()

        executor(ScriptedResponder(ArrayDeque(listOf(failure)))).executeNext()

        val state = job().state
        assertThat(state.status).isEqualTo(JobStatus.PENDING)
        assertThat(state.attemptCount).isEqualTo(1)
        // 첫 backoff 10초 + 지터 최대 20%
        assertThat(state.nextAttemptAt).isBetween(start.plusSeconds(10), start.plusSeconds(12))
        assertThat(assistantMessages()).isEmpty()
    }

    @Test
    fun `새 입력 없는 최종 실패는 재개 가능 시각을 남기고 반복하지 않는다`() {
        accept()
        val runner = executor(ScriptedResponder(ArrayDeque(listOf(unauthorized))))

        runner.executeNext()

        assertThat(job().state.status).isEqualTo(JobStatus.FAILED)
        assertThat(job().state.nextAttemptAt).isEqualTo(policy.reopenAt(1, start, null))
        advance(Duration.ofHours(1))
        assertThat(runner.executeNext()).isFalse()
    }

    @Test
    fun `최종 실패 뒤 새 메시지는 재개 가능 시각을 지키며 실패한 입력과 함께 새 실행 주기로 답한다`() {
        accept("첫 번째")
        val responder = ScriptedResponder(ArrayDeque(listOf(unauthorized, answer("함께 답함"))))
        val runner = executor(responder)
        runner.executeNext()
        val reopenAt = job().state.nextAttemptAt

        accept("두 번째")
        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job().state.attemptCount).isZero()
        assertThat(job().state.nextAttemptAt).isEqualTo(reopenAt)
        assertThat(runner.executeNext()).isFalse()

        timeProvider.fixedNow = reopenAt
        runner.executeNext()
        assertThat(job().observedSourceRevision).isEqualTo(2)
        assertThat(responder.requests.last().second.map { it.content }).containsExactly("첫 번째", "두 번째")
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `실행 중 접수 뒤 최종 실패하면 접수 시점과 관계없이 재개하고 공급자 대기를 지킨다`() {
        accept("첫 번째")
        val limited = { accept("두 번째"); throw LlmException("sanitized", errorCode = "HTTP_401") }
        executor(ScriptedResponder(ArrayDeque(listOf(limited)))).executeNext()

        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job().state.attemptCount).isZero()
        assertThat(job().state.nextAttemptAt).isEqualTo(policy.reopenAt(1, start, null))
    }

    @Test
    fun `429 최종 실패의 재개 가능 시각은 공급자 Retry-After를 지킨다`() {
        val properties = testResponseJobProperties.copy(maxAttempts = 1)
        accept()
        val runner = ResponseJobExecutor(jobRepository, messageRepository,
            ScriptedResponder(ArrayDeque(listOf {
                throw LlmException(
                    "sanitized",
                    errorCode = "HTTP_429",
                    retryAfter = Duration.ofMinutes(3)
                )
            })),
            SaveGeneratedResponseService(messageRepository, jobRepository, timeProvider, records),
            MarkResponseFailedService(jobRepository, records, properties, timeProvider), timeProvider, ConversationLimitProperties())

        runner.executeNext()

        assertThat(job().state.status).isEqualTo(JobStatus.FAILED)
        assertThat(job().state.nextAttemptAt).isEqualTo(start.plus(Duration.ofMinutes(3)))
        accept("다시")
        assertThat(job().state.nextAttemptAt).isEqualTo(start.plus(Duration.ofMinutes(3)))
    }

    @Test
    fun `미응답 입력이 예산을 넘으면 오래된 순으로 나눠 답하고 누락·중복이 없다`() {
        val limits = ConversationLimitProperties(maxMessageLength = 10, contextCharBudget = 20)
        listOf("가".repeat(10), "나".repeat(10), "다".repeat(10)).forEach { accept(it) }
        val responder = ScriptedResponder(ArrayDeque(listOf(answer("앞 두 개"), answer("마지막"))))
        val runner = executor(responder, limits = limits)

        runner.executeNext()
        runner.executeNext()

        assertThat(responder.requests.map { request -> request.second.map { it.sourceSequence } })
            .containsExactly(listOf(1L, 2L), listOf(3L))
        assertThat(assistantMessages().map { it.sourceSequence }).containsExactly(2L, 3L)
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `한 호출의 메시지 수 상한은 답변 대상에 먼저 배정하고 남은 수로 과거 문맥을 채운다`() {
        val limits = ConversationLimitProperties(contextMessageLimit = 3)
        accept("첫 번째")
        val responder = ScriptedResponder(ArrayDeque(listOf(answer("첫 응답"), answer("묶음 응답"), answer("마지막 응답"))))
        val runner = executor(responder, limits = limits)
        runner.executeNext()
        listOf("둘", "셋", "넷", "다섯").forEach { accept(it) }

        runner.executeNext()
        runner.executeNext()

        assertThat(responder.requests).allSatisfy { (history, targets) -> assertThat(history.size + targets.size).isLessThanOrEqualTo(3) }
        assertThat(responder.requests[1].first).isEmpty()
        assertThat(responder.requests[1].second.map { it.content }).containsExactly("둘", "셋", "넷")
        assertThat(responder.requests[2].first.map { it.content }).containsExactly("넷", "묶음 응답")
        assertThat(responder.requests[2].second.map { it.content }).containsExactly("다섯")
        assertThat(assistantMessages().map { it.sourceSequence }).containsExactly(1L, 4L, 5L)
    }

    @Test
    fun `같은 작업을 두 번 실행해도 LLM은 한 번만 불린다`() {
        accept()
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))
        val executor = executor(responder)

        executor.executeNext()
        executor.executeNext()

        assertThat(responder.requests).hasSize(1)
        assertThat(assistantMessages()).hasSize(1)
    }

    @Test
    fun `리스 만료로 정리된 뒤 도착한 늦은 결과와 실패는 버려지고 FAILED가 유지된다`() {
        accept("첫 번째")
        // LLM 호출이 리스(2분)보다 오래 걸리는 사이 정리 트랜잭션이 작업을 FAILED로 바꾸는 상황
        lateinit var runner: ResponseJobExecutor
        val slowThenExpired = { ok: Boolean ->
            {
                advance(Duration.ofMinutes(3))
                runner.failExpiredLeases()
                if (ok) answer("늦은 응답").invoke() else throw LlmException("늦은 실패")
            }
        }

        runner = executor(ScriptedResponder(ArrayDeque(listOf(slowThenExpired(true)))))
        runner.executeNext()
        assertThat(assistantMessages()).isEmpty()
        assertThat(job().state.status).isEqualTo(JobStatus.FAILED)

        // 새 메시지로 재개한 실행도 같은 방식으로 멈추면 늦은 실패 기록이 FAILED를 덮지 않는다
        accept("두 번째")
        advance(policy.reopenCooldown)
        runner = executor(ScriptedResponder(ArrayDeque(listOf(slowThenExpired(false)))))
        runner.executeNext()
        assertThat(job().state.status).isEqualTo(JobStatus.FAILED)
        assertThat(job().state.attemptCount).isEqualTo(1)
        assertThat(job().observedSourceRevision).isEqualTo(2)
    }

    @Test
    fun `리스가 만료된 작업은 다시 실행하지 않고 FAILED로 정리하며 멈춘 동안 접수가 있으면 새 실행 주기를 연다`() {
        accept("첫 번째")
        val responder = ScriptedResponder(ArrayDeque(listOf(answer())))
        val runner = executor(responder)
        jobRepository.claimBatch(start, 1).single()

        advance(policy.lease.minusSeconds(1))
        runner.failExpiredLeases()
        assertThat(job().state.status).isEqualTo(JobStatus.PROCESSING)

        advance(Duration.ofSeconds(1))
        runner.failExpiredLeases()
        assertThat(job().state.status).isEqualTo(JobStatus.FAILED)
        assertThat(job().state.attemptCount).isEqualTo(1)
        advance(Duration.ofHours(1))
        assertThat(runner.executeNext()).isFalse()
        assertThat(responder.requests).isEmpty()

        // 새 메시지로 재개한 실행이 다시 멈추고, 그 사이 또 새 입력이 접수된 경우
        accept("두 번째")
        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        jobRepository.claimBatch(timeProvider.fixedNow, 1).single()
        accept("세 번째")
        advance(policy.lease)
        runner.failExpiredLeases()
        assertThat(job().state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job().state.attemptCount).isZero()

        advance(Duration.ofMinutes(2))
        assertThat(runner.executeNext()).isTrue()
        assertThat(responder.requests.single().second.map { it.content }).containsExactly("첫 번째", "두 번째", "세 번째")
        assertThat(job().state.status).isEqualTo(JobStatus.COMPLETED)
    }

    @Test
    fun `계속 실패해도 실행 기회는 새 입력마다만 다시 열리고 호출 수는 여유 상한 안이다`() {
        val inputs = 3
        val calls = AtomicInteger()
        val alwaysFailing = object : ConversationResponder {
            override fun respond(history: List<Message>, targets: List<Message>): ConversationResponse {
                calls.incrementAndGet()
                throw LlmException("LLM 호출 실패")
            }
        }
        val runner = executor(alwaysFailing)
        val observed = mutableListOf<Long>()
        repeat(inputs) { index ->
            accept("입력 $index")
            repeat(50) {
                advance(Duration.ofMinutes(10))
                val claimed = runner.claim(1).singleOrNull()
                if (claimed != null) {
                    if (claimed.state.attemptCount == 1) observed += claimed.observedSourceRevision
                    runner.execute(claimed)
                }
            }
            assertThat(job().state.status).isEqualTo(JobStatus.FAILED)
        }

        // 새 입력에 의한 재개는 다음 실행 주기의 선점에서 관측 상한을 전진시킨다
        assertThat(observed).containsExactly(1L, 2L, 3L)
        assertThat(calls.get()).isLessThanOrEqualTo((2 * inputs + 1) * testResponseJobProperties.maxAttempts)
    }
}
