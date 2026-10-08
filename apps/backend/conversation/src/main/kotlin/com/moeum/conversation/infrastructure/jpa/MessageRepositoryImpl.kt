package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import jakarta.persistence.EntityManager
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
    private val entityManager: EntityManager,
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

    // 직접 할당한 UUID라 Spring Data save()는 merge로 처리되어 INSERT 전에 SELECT가 한 번 더 나간다.
    // 추가만 하는 데이터이므로 persist로 바로 INSERT한다(호출부 트랜잭션 안에서 실행된다).
    override fun append(message: Message): Message {
        entityManager.persist(message.toEntity())
        return message
    }
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
