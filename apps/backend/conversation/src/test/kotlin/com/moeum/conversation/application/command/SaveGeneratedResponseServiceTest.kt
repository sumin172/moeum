package com.moeum.conversation.application.command

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.llm.conversation.ConversationResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SaveGeneratedResponseServiceTest {

    private class FakeMessageRepository : MessageRepository {
        val messages = mutableMapOf<MessageId, Message>()
        override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? = null
        override fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message> = emptyList()
        override fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message> = emptyList()
        override fun save(message: Message): Message {
            messages[message.id] = message
            return message
        }
        override fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean =
            error("not used in this test")
    }

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    @Test
    fun `assistant 메시지를 저장하고 유저 메시지를 COMPLETED로 갱신한다`() {
        val messageRepository = FakeMessageRepository()
        val service = SaveGeneratedResponseService(
            messageRepository = messageRepository,
            timeProvider = FixedTimeProvider(Instant.parse("2026-08-08T10:00:05Z")),
        )
        val userMessage = Message.userMessage(
            id = MessageId.generate(),
            conversationDayId = ConversationDayId.generate(),
            userId = UserId.generate(),
            content = "오늘 정말 피곤했다",
            occurredAt = Instant.parse("2026-08-08T10:00:00Z"),
            timezone = "Asia/Seoul",
            localDate = LocalDate.of(2026, 8, 8),
            clientMessageId = UUID.randomUUID(),
        )
        val response = ConversationResponse(
            generationId = UUID.randomUUID(),
            content = "많이 힘드셨겠어요.",
            model = "gemini-2.5-flash",
            provider = "google",
            promptVersion = "v1",
            inputTokens = 10,
            outputTokens = 5,
        )

        service.save(userMessage, response)

        val assistantMessage = messageRepository.messages.values.single { it.role == MessageRole.ASSISTANT }
        assertThat(assistantMessage.content).isEqualTo("많이 힘드셨겠어요.")
        assertThat(assistantMessage.conversationDayId).isEqualTo(userMessage.conversationDayId)
        val savedUserMessage = messageRepository.messages.getValue(userMessage.id)
        assertThat(savedUserMessage.responseStatus).isEqualTo(MessageResponseStatus.COMPLETED)
    }
}
