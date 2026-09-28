package com.moeum.conversation.application.command

import com.moeum.conversation.application.publicapi.events.MomentsPreparedV1
import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.MomentExtractionJobRepository
import com.moeum.conversation.domain.MomentRepository
import com.moeum.conversation.domain.MomentSetRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.conversation.domain.model.Moment
import com.moeum.conversation.domain.model.MomentExtractionJob
import com.moeum.conversation.domain.model.MomentExtractionJobStatus
import com.moeum.conversation.domain.model.MomentId
import com.moeum.conversation.domain.model.MomentSet
import com.moeum.conversation.domain.model.MomentSetId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.llm.moment.ExtractedMoment
import com.moeum.platform.llm.moment.MomentExtractionException
import com.moeum.platform.llm.moment.MomentExtractionRequest
import com.moeum.platform.llm.moment.MomentExtractionResponse
import com.moeum.platform.llm.moment.MomentExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MomentExtractionServiceTest {

    private val userId = UserId.generate()
    private val conversationDayId = ConversationDayId.generate()
    private val localDate: LocalDate = LocalDate.of(2026, 9, 28)
    private val now: Instant = Instant.parse("2026-09-28T15:00:00Z")
    private val correlationId: UUID = UUID.randomUUID()

    private fun newDay(sourceRevision: Long): ConversationDay =
        ConversationDay.open(id = conversationDayId, userId = userId, localDate = localDate, timezone = "Asia/Seoul", now = now)
            .let { day -> generateSequence(day) { it.withMessageAdded("Asia/Seoul") }.take((sourceRevision + 1).toInt()).last() }

    private fun newMessage(content: String): Message =
        Message.userMessage(
            id = MessageId.generate(),
            conversationDayId = conversationDayId,
            userId = userId,
            content = content,
            occurredAt = now,
            timezone = "Asia/Seoul",
            localDate = localDate,
            clientMessageId = UUID.randomUUID(),
        )

    @Test
    fun `이미 같은 revision으로 추출된 적 있으면 재호출해도 새로 추출하지 않고 기존 결과를 반환한다`() {
        val day = newDay(sourceRevision = 2)
        val existingMomentSet = MomentSet(
            id = MomentSetId.generate(),
            conversationDayId = conversationDayId,
            sourceRevision = 2,
            generationId = UUID.randomUUID(),
            model = "gemini-flash-latest",
            promptVersion = "v1",
            createdAt = now,
        )
        val existingMoment = Moment(
            id = MomentId.generate(),
            momentSetId = existingMomentSet.id,
            conversationDayId = conversationDayId,
            type = "MEAL",
            summary = "이미 뽑아둔 Moment",
        )
        val momentSetRepository = FakeMomentSetRepository(existing = listOf(existingMomentSet))
        val momentRepository = FakeMomentRepository(existing = listOf(existingMoment))
        val extractor = FakeMomentExtractor()

        val service = newService(day = day, momentSetRepository = momentSetRepository, momentRepository = momentRepository, extractor = extractor)

        val result = service.ensureExtracted(conversationDayId, correlationId)

        assertThat(result).containsExactly(existingMoment)
        assertThat(extractor.wasCalled).isFalse() // 재추출 안 함
    }

    @Test
    fun `처음 호출이면 그 순간 원본 메시지를 다시 읽어 추출하고 결과를 저장·발행한다`() {
        val day = newDay(sourceRevision = 3) // revision이 여러 번 올라간 상황을 가정해도 동작은 동일함을 보여주는 값
        val extractor = FakeMomentExtractor(
            MomentExtractionResponse(
                generationId = UUID.randomUUID(),
                moments = listOf(ExtractedMoment(type = "MEAL", summary = "점심으로 국밥을 먹음")),
                model = "gemini-flash-latest",
                provider = "google",
                promptVersion = "v1",
                inputTokens = 10,
                outputTokens = 5,
            ),
        )
        val momentSetRepository = FakeMomentSetRepository()
        val eventPublisher = RecordingEventPublisher()

        val service = newService(
            day = day,
            messages = listOf(newMessage("오늘 점심은 국밥이었다")),
            momentSetRepository = momentSetRepository,
            extractor = extractor,
            eventPublisher = eventPublisher,
        )

        val result = service.ensureExtracted(conversationDayId, correlationId)

        assertThat(extractor.wasCalled).isTrue()
        assertThat(extractor.lastRequest?.rawTranscript).contains("오늘 점심은 국밥이었다")
        assertThat(result).hasSize(1)
        assertThat(momentSetRepository.saved.single().sourceRevision).isEqualTo(3) // 실행 시점에 읽은 day의 revision

        val published = eventPublisher.published.single() as MomentsPreparedV1
        assertThat(published.sourceRevision).isEqualTo(3)
        assertThat(published.correlationId).isEqualTo(correlationId) // 호출부가 준 값이 그대로 흘러간다
    }

    @Test
    fun `추출 실패 시 Job이 FAILED로 기록되고 빈 목록을 반환한다`() {
        val day = newDay(sourceRevision = 1)
        val jobRepository = FakeMomentExtractionJobRepository()

        val service = newService(day = day, jobRepository = jobRepository, extractor = FailingMomentExtractor())

        val result = service.ensureExtracted(conversationDayId, correlationId)

        assertThat(result).isEmpty()
        val job = jobRepository.saved.single()
        assertThat(job.status).isEqualTo(MomentExtractionJobStatus.FAILED)
        assertThat(job.errorCode).isEqualTo("MomentExtractionException")
    }

    private fun newService(
        day: ConversationDay,
        messages: List<Message> = emptyList(),
        momentSetRepository: FakeMomentSetRepository = FakeMomentSetRepository(),
        momentRepository: FakeMomentRepository = FakeMomentRepository(),
        jobRepository: FakeMomentExtractionJobRepository = FakeMomentExtractionJobRepository(),
        extractor: MomentExtractor,
        eventPublisher: RecordingEventPublisher = RecordingEventPublisher(),
    ): MomentExtractionService {
        val timeProvider = FixedTimeProvider(now)
        return MomentExtractionService(
            conversationDayRepository = FakeConversationDayRepository(day),
            messageRepository = FakeMessageRepository(messages),
            momentSetRepository = momentSetRepository,
            momentRepository = momentRepository,
            momentExtractionJobRepository = jobRepository,
            momentExtractor = extractor,
            saveMomentExtractionResultService = SaveMomentExtractionResultService(
                momentSetRepository, momentRepository, jobRepository, eventPublisher, timeProvider,
            ),
            markMomentExtractionFailedService = MarkMomentExtractionFailedService(jobRepository),
            timeProvider = timeProvider,
        )
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published.add(event)
        }
    }

    private class FakeConversationDayRepository(private val day: ConversationDay) : ConversationDayRepository {
        override fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay = day
        override fun findById(id: ConversationDayId): ConversationDay = day
        override fun save(conversationDay: ConversationDay): ConversationDay = conversationDay
    }

    private class FakeMessageRepository(private val messages: List<Message>) : MessageRepository {
        override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? = null
        override fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message> = emptyList()
        override fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message> = messages
        override fun save(message: Message): Message = message
        override fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean = true
    }

    private class FakeMomentSetRepository(existing: List<MomentSet> = emptyList()) : MomentSetRepository {
        val saved = mutableListOf<MomentSet>().apply { addAll(existing) }
        override fun save(momentSet: MomentSet): MomentSet {
            saved.add(momentSet)
            return momentSet
        }
        override fun findByConversationDayIdAndSourceRevision(conversationDayId: ConversationDayId, sourceRevision: Long): MomentSet? =
            saved.find { it.conversationDayId == conversationDayId && it.sourceRevision == sourceRevision }
    }

    private class FakeMomentRepository(existing: List<Moment> = emptyList()) : MomentRepository {
        val saved = mutableListOf<Moment>().apply { addAll(existing) }
        override fun saveAll(moments: List<Moment>): List<Moment> {
            saved.addAll(moments)
            return moments
        }
        override fun findAllByMomentSetId(momentSetId: MomentSetId): List<Moment> = saved.filter { it.momentSetId == momentSetId }
    }

    private class FakeMomentExtractionJobRepository : MomentExtractionJobRepository {
        val saved = mutableListOf<MomentExtractionJob>()
        override fun save(job: MomentExtractionJob): MomentExtractionJob {
            val isDuplicate = saved.any { it.requestKey == job.requestKey && it.id != job.id }
            if (isDuplicate) throw DataIntegrityViolationException("duplicate request_key: ${job.requestKey}")
            saved.removeAll { it.id == job.id }
            saved.add(job)
            return job
        }
    }

    private class FakeMomentExtractor(private val response: MomentExtractionResponse? = null) : MomentExtractor {
        var wasCalled = false
            private set
        var lastRequest: MomentExtractionRequest? = null
            private set

        override fun extract(request: MomentExtractionRequest): MomentExtractionResponse {
            wasCalled = true
            lastRequest = request
            return response ?: error("response not stubbed")
        }
    }

    private class FailingMomentExtractor : MomentExtractor {
        override fun extract(request: MomentExtractionRequest): MomentExtractionResponse {
            throw MomentExtractionException("추출 실패")
        }
    }

    private class FixedTimeProvider(private val instant: Instant) : TimeProvider {
        override fun now(): Instant = instant
        override fun today(zoneId: ZoneId): LocalDate = instant.atZone(zoneId).toLocalDate()
    }
}
