package com.moeum.conversation.application.command

import com.moeum.conversation.domain.AiUsageRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.conversation.infrastructure.config.AiQuotaProperties
import com.moeum.platform.llm.conversation.ConversationRequest
import com.moeum.platform.llm.conversation.ConversationResponder
import com.moeum.platform.llm.conversation.LlmMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class GenerateConversationResponseService(
    private val messageRepository: MessageRepository,
    private val aiUsageRepository: AiUsageRepository,
    private val conversationResponder: ConversationResponder,
    private val aiQuotaProperties: AiQuotaProperties,
    private val saveGeneratedResponseService: SaveGeneratedResponseService,
) {
    private val log = LoggerFactory.getLogger(GenerateConversationResponseService::class.java)

    @Async
    fun generateAsync(userMessage: Message) {
        val claimed = messageRepository.compareAndSetStatus(
            userMessage.id,
            MessageResponseStatus.PENDING,
            MessageResponseStatus.PROCESSING,
        )
        if (!claimed) {
            log.info("이미 다른 요청이 처리 중이거나 처리를 마친 메시지라 스킵: messageId={}", userMessage.id.value)
            return
        }

        val attemptCount = aiUsageRepository.recordAttempt(userMessage.userId, userMessage.localDate)
        if (attemptCount > aiQuotaProperties.dailyMessageLimit) {
            log.warn(
                "일일 AI 응답 quota 초과: userId={}, localDate={}, attemptCount={}, limit={}",
                userMessage.userId.value,
                userMessage.localDate,
                attemptCount,
                aiQuotaProperties.dailyMessageLimit,
            )
            messageRepository.save(userMessage.withResponseStatus(MessageResponseStatus.FAILED))
            return
        }

        val context = messageRepository.findAllByConversationDayId(userMessage.conversationDayId)
            .map { LlmMessage(role = it.role.name.lowercase(), content = it.content) }

        try {
            val response = conversationResponder.respond(ConversationRequest(messages = context))
            saveGeneratedResponseService.save(userMessage, response)
        } catch (e: Exception) {
            log.error(
                "AI 응답 생성 실패: userId={}, conversationDayId={}, messageId={}, error={}",
                userMessage.userId.value,
                userMessage.conversationDayId.value,
                userMessage.id.value,
                e.message,
                e,
            )
            messageRepository.save(userMessage.withResponseStatus(MessageResponseStatus.FAILED))
        }
    }
}
