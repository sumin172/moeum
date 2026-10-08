package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaveGeneratedResponseService(
    private val messageRepository: MessageRepository,
    private val responseJobRepository: ResponseJobRepository,
    private val timeProvider: TimeProvider,
) {
    // 작업 완료를 먼저 저장한다 — 리스를 뺏긴 워커면 여기서 낙관적 락 충돌로 트랜잭션 전체가 롤백되어
    // assistant 메시지가 중복 저장되지 않는다.
    @Transactional
    fun save(job: ResponseJob, userMessage: Message, response: ConversationResponse) {
        val now = timeProvider.now()
        responseJobRepository.save(job.completed(now))
        messageRepository.append(
            Message.assistantMessage(
                id = MessageId.generate(),
                userId = userMessage.userId,
                content = response.content,
                occurredAt = now,
                timezone = userMessage.timezone,
                localDate = userMessage.localDate,
                dayDate = userMessage.dayDate,
                generationId = response.generationId,
                model = response.model,
                promptVersion = response.promptVersion,
                inputTokens = response.inputTokens,
                outputTokens = response.outputTokens,
            ),
        )
    }
}
