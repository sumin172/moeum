package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 커서는 id(UUIDv7, 서버 발급 순)로 비교하고, 반환은 실제 발화 순서(occurredAt)로 정렬한다 —
// 오프라인으로 늦게 도착한 메시지도 발화 시점 위치에 보이게 하기 위함.
private val DISPLAY_ORDER = compareBy<Message>({ it.occurredAt }, { it.id.value })

@Component
class MessageRepositoryImpl(
    private val jpaRepository: MessageJpaRepository,
) : MessageRepository {

    override fun findByUserIdAndId(userId: UserId, id: MessageId): Message? =
        jpaRepository.findByUserIdAndId(userId.value, id.value)?.toDomain()

    override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
        jpaRepository.findByUserIdAndClientMessageId(userId.value, clientMessageId)?.toDomain()

    override fun findPage(userId: UserId, dayDate: LocalDate, after: MessageId?, limit: Int): List<Message> {
        val pageable = PageRequest.of(0, limit)
        val entities = if (after != null) {
            jpaRepository.findByUserIdAndDayDateAndIdGreaterThanOrderByIdAsc(userId.value, dayDate, after.value, pageable)
        } else {
            jpaRepository.findByUserIdAndDayDateOrderByIdDesc(userId.value, dayDate, pageable).asReversed()
        }
        return entities.map { it.toDomain() }.sortedWith(DISPLAY_ORDER)
    }

    override fun findAllByUserIdAndDayDate(userId: UserId, dayDate: LocalDate): List<Message> =
        jpaRepository.findByUserIdAndDayDateOrderByIdAsc(userId.value, dayDate).map { it.toDomain() }.sortedWith(DISPLAY_ORDER)

    override fun findCreatedBetween(from: Instant, to: Instant): List<Message> =
        jpaRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to).map { it.toDomain() }

    override fun save(message: Message): Message =
        jpaRepository.save(message.toEntity()).toDomain()
}

private fun MessageJpaEntity.toDomain(): Message =
    Message(
        id = MessageId(id),
        userId = UserId(userId),
        role = role,
        content = content,
        occurredAt = occurredAt,
        timezone = timezone,
        localDate = localDate,
        dayDate = dayDate,
        clientMessageId = clientMessageId,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )

private fun Message.toEntity(): MessageJpaEntity =
    MessageJpaEntity(
        id = id.value,
        userId = userId.value,
        role = role,
        content = content,
        occurredAt = occurredAt,
        timezone = timezone,
        localDate = localDate,
        dayDate = dayDate,
        clientMessageId = clientMessageId,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )
