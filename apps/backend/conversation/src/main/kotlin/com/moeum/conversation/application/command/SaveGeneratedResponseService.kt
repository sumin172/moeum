package com.moeum.conversation.application.command

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.kernel.TimeProvider
import com.moeum.platform.llm.conversation.ConversationResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaveGeneratedResponseService(
    private val messageRepository: MessageRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun save(userMessage: Message, response: ConversationResponse) {
        messageRepository.save(
            Message.assistantMessage(
                id = MessageId.generate(),
                conversationDayId = userMessage.conversationDayId,
                userId = userMessage.userId,
                content = response.content,
                occurredAt = timeProvider.now(),
                timezone = userMessage.timezone,
                localDate = userMessage.localDate,
                generationId = response.generationId,
                model = response.model,
                promptVersion = response.promptVersion,
                inputTokens = response.inputTokens,
                outputTokens = response.outputTokens,
            ),
        )
        messageRepository.save(userMessage.withResponseStatus(MessageResponseStatus.COMPLETED))
    }
}
