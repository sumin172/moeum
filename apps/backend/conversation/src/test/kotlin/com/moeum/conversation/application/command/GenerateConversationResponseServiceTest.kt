package com.moeum.conversation.application.command

import com.moeum.conversation.domain.AiUsageRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.infrastructure.config.AiQuotaProperties
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.llm.conversation.ConversationRequest
import com.moeum.platform.llm.conversation.ConversationResponder
import com.moeum.platform.llm.conversation.ConversationResponse
import com.moeum.platform.llm.conversation.ConversationResponseException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class GenerateConversationResponseServiceTest {

    private class FakeMessageRepository : MessageRepository {
        val messages = mutableMapOf<MessageId, Message>()
        override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
            messages.values.find { it.userId == userId && it.clientMessageId == clientMessageId }
        override fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message> =
            messages.values.filter { it.conversationDayId == conversationDayId }
        override fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message> =
            messages.values.filter { it.conversationDayId == conversationDayId }
                .sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
        override fun save(message: Message): Message {
            messages[message.id] = message
            return message
        }
        override fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean {
            val current = messages[id] ?: return false
            if (current.responseStatus != expected) return false
            messages[id] = current.withResponseStatus(updated)
            return true
        }
    }

    private class FakeAiUsageRepository(private val startingCount: Int = 0) : AiUsageRepository {
        var callCount = 0
        override fun recordAttempt(userId: UserId, date: LocalDate): Int {
            callCount++
            return startingCount + callCount
        }
    }

    private class FakeConversationResponder(
        private val result: Result<ConversationResponse>,
    ) : ConversationResponder {
        var called = false
        override fun respond(request: ConversationRequest): ConversationResponse {
            called = true
            return result.getOrThrow()
        }
    }

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    private val userId = UserId.generate()
    private val conversationDayId = ConversationDayId.generate()
    private val localDate = LocalDate.of(2026, 8, 8)

    private fun newUserMessage(): Message =
        Message.userMessage(
            id = MessageId.generate(),
            conversationDayId = conversationDayId,
            userId = userId,
            content = "오늘 정말 피곤했다",
            occurredAt = Instant.parse("2026-08-08T10:00:00Z"),
            timezone = "Asia/Seoul",
            localDate = localDate,
            clientMessageId = UUID.randomUUID(),
        )

    private fun newService(
        messageRepository: FakeMessageRepository,
        aiUsageRepository: AiUsageRepository = FakeAiUsageRepository(),
        conversationResponder: ConversationResponder,
        dailyMessageLimit: Int = 20,
    ) = GenerateConversationResponseService(
        messageRepository = messageRepository,
        aiUsageRepository = aiUsageRepository,
        conversationResponder = conversationResponder,
        aiQuotaProperties = AiQuotaProperties(dailyMessageLimit = dailyMessageLimit),
        saveGeneratedResponseService = SaveGeneratedResponseService(
            messageRepository = messageRepository,
            timeProvider = FixedTimeProvider(Instant.parse("2026-08-08T10:00:05Z")),
        ),
    )

    @Test
    fun `Gemini 호출이 성공하면 assistant 메시지를 저장하고 유저 메시지를 COMPLETED로 갱신한다`() {
        val messageRepository = FakeMessageRepository()
        val userMessage = newUserMessage()
        messageRepository.save(userMessage)
        val response = ConversationResponse(
            generationId = UUID.randomUUID(),
            content = "많이 힘드셨겠어요.",
            model = "gemini-2.5-flash",
            provider = "google",
            promptVersion = "v1",
            inputTokens = 10,
            outputTokens = 5,
        )
        val service = newService(
            messageRepository = messageRepository,
            conversationResponder = FakeConversationResponder(Result.success(response)),
        )

        service.generateAsync(userMessage)

        val savedUserMessage = messageRepository.messages.getValue(userMessage.id)
        assertThat(savedUserMessage.responseStatus).isEqualTo(MessageResponseStatus.COMPLETED)
        val assistantMessage = messageRepository.messages.values.single { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.content).isEqualTo("많이 힘드셨겠어요.")
        assertThat(assistantMessage.conversationDayId).isEqualTo(conversationDayId)
    }

    @Test
    fun `Gemini 호출이 실패하면 유저 메시지를 FAILED로 갱신하고 assistant 메시지는 저장하지 않는다`() {
        val messageRepository = FakeMessageRepository()
        val userMessage = newUserMessage()
        messageRepository.save(userMessage)
        val service = newService(
            messageRepository = messageRepository,
            conversationResponder = FakeConversationResponder(Result.failure(ConversationResponseException("서킷 오픈"))),
        )

        service.generateAsync(userMessage)

        val savedUserMessage = messageRepository.messages.getValue(userMessage.id)
        assertThat(savedUserMessage.responseStatus).isEqualTo(MessageResponseStatus.FAILED)
        assertThat(messageRepository.messages.values.none { it.role == MessageRole.ASSISTANT }).isTrue()
    }

    @Test
    fun `일일 quota를 초과하면 Gemini를 호출하지 않고 유저 메시지를 FAILED로 갱신한다`() {
        val messageRepository = FakeMessageRepository()
        val userMessage = newUserMessage()
        messageRepository.save(userMessage)
        val responder = FakeConversationResponder(
            Result.success(
                ConversationResponse(UUID.randomUUID(), "안 불려야 함", "gemini-2.5-flash", "google", "v1", 1, 1),
            ),
        )
        val service = newService(
            messageRepository = messageRepository,
            aiUsageRepository = FakeAiUsageRepository(startingCount = 100),
            conversationResponder = responder,
            dailyMessageLimit = 5,
        )

        service.generateAsync(userMessage)

        assertThat(responder.called).isFalse()
        val savedUserMessage = messageRepository.messages.getValue(userMessage.id)
        assertThat(savedUserMessage.responseStatus).isEqualTo(MessageResponseStatus.FAILED)
    }

    @Test
    fun `같은 메시지에 대해 동시에 두 번 호출돼도 Gemini는 한 번만 불린다`() {
        val messageRepository = FakeMessageRepository()
        val userMessage = newUserMessage()
        messageRepository.save(userMessage)
        val response = ConversationResponse(UUID.randomUUID(), "답변", "gemini-2.5-flash", "google", "v1", 1, 1)
        val responder = FakeConversationResponder(Result.success(response))
        val service = newService(messageRepository = messageRepository, conversationResponder = responder)

        // clientMessageId 재전송으로 두 요청이 동시에 같은(PENDING) 메시지를 들고 generateAsync를 호출하는 상황을 재현.
        service.generateAsync(userMessage)
        service.generateAsync(userMessage)

        assertThat(messageRepository.messages.values.count { it.role == MessageRole.ASSISTANT }).isEqualTo(1)
    }
}
