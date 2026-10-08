package com.moeum.conversation.application.command

import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import com.moeum.platform.llm.conversation.ConversationResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class SaveGeneratedResponseServiceTest {

    @Test
    fun `assistant 메시지를 저장하고 유저 메시지를 COMPLETED로 갱신한다`() {
        val messageRepository = InMemoryMessageRepository()
        val service = SaveGeneratedResponseService(
            messageRepository = messageRepository,
            timeProvider = FixedTimeProvider(Instant.parse("2026-08-08T10:00:05Z")),
        )
        val userMessage = userMessage(UserId.generate(), "오늘 정말 피곤했다", Instant.parse("2026-08-08T10:00:00Z"), LocalDate.of(2026, 8, 8))
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
        assertThat(assistantMessage.dayDate).isEqualTo(userMessage.dayDate)
        val savedUserMessage = messageRepository.messages.getValue(userMessage.id)
        assertThat(savedUserMessage.responseStatus).isEqualTo(MessageResponseStatus.COMPLETED)
    }
}
