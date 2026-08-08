package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.kernel.UserId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MessageRepositoryImpl(
    private val jpaRepository: MessageJpaRepository,
) : MessageRepository {

    override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
        jpaRepository.findByUserIdAndClientMessageId(userId.value, clientMessageId)?.toDomain()

    override fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message> {
        val pageable = PageRequest.of(0, limit)
        val entities = if (after != null) {
            jpaRepository.findByConversationDayIdAndIdGreaterThanOrderByIdAsc(conversationDayId.value, after.value, pageable)
        } else {
            jpaRepository.findByConversationDayIdOrderByIdDesc(conversationDayId.value, pageable).asReversed()
        }
        return entities.map { it.toDomain() }.sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
    }

    override fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message> =
        jpaRepository.findByConversationDayIdOrderByIdAsc(conversationDayId.value).map { it.toDomain() }

    override fun save(message: Message): Message =
        jpaRepository.save(message.toEntity()).toDomain()

    override fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean =
        jpaRepository.compareAndSetStatus(id.value, expected, updated) > 0
}

private fun MessageJpaEntity.toDomain(): Message =
    Message(
        id = MessageId(id),
        conversationDayId = ConversationDayId(conversationDayId),
        userId = UserId(userId),
        role = role,
        content = content,
        occurredAt = occurredAt,
        timezone = timezone,
        localDate = localDate,
        clientMessageId = clientMessageId,
        responseStatus = responseStatus,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        deletedAt = deletedAt,
    )

private fun Message.toEntity(): MessageJpaEntity =
    MessageJpaEntity(
        id = id.value,
        conversationDayId = conversationDayId.value,
        userId = userId.value,
        role = role,
        content = content,
        occurredAt = occurredAt,
        timezone = timezone,
        localDate = localDate,
        clientMessageId = clientMessageId,
        responseStatus = responseStatus,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        deletedAt = deletedAt,
    )
