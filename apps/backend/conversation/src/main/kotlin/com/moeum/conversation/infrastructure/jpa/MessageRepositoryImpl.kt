package com.moeum.conversation.infrastructure.jpa

import com.moeum.kernel.RecordDayId

import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

// 표시 시각과 별개인 streamSequence로 커밋 가시성을 추적한다.
private val DISPLAY_ORDER = compareBy<Message>({ it.occurredAt }, { it.streamSequence })

@Component
class MessageRepositoryImpl(
    private val jpaRepository: MessageJpaRepository,
    private val entityManager: EntityManager,
    private val jdbc: org.springframework.jdbc.core.JdbcTemplate,
) : MessageRepository {

    override fun findByUserIdAndId(userId: UserId, id: MessageId): Message? =
        jpaRepository.findByUserIdAndId(userId.value, id.value)?.toDomain()

    override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
        jpaRepository.findByUserIdAndClientMessageId(userId.value, clientMessageId)?.toDomain()

    override fun findPage(userId: UserId, recordDayId: RecordDayId, after: Long?, limit: Int): List<Message> {
        val pageable = PageRequest.of(0, limit)
        val entities = if (after != null) {
            jpaRepository.findByUserIdAndRecordDayIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(userId.value, recordDayId.value, after, pageable)
        } else {
            jpaRepository.findByUserIdAndRecordDayIdOrderByStreamSequenceDesc(userId.value, recordDayId.value, pageable).asReversed()
        }
        return entities.map { it.toDomain() }.sortedWith(DISPLAY_ORDER)
    }

    override fun findContext(userId: UserId, recordDayId: RecordDayId, inputSourceRevision: Long, limit: Int): List<Message> =
        jpaRepository.findByUserIdAndRecordDayIdAndSourceSequenceLessThanEqualOrderBySourceSequenceDescRoleAsc(
            userId.value, recordDayId.value, inputSourceRevision, PageRequest.of(0,limit),
        ).asReversed().map { it.toDomain() }


    override fun findSource(userId: UserId, recordDayId: RecordDayId, sourceRevision: Long): List<Message> =
        jpaRepository.findByUserIdAndRecordDayIdAndRoleAndSourceSequenceLessThanEqualOrderByOccurredAtAscSourceSequenceAsc(
            userId.value, recordDayId.value, com.moeum.conversation.domain.model.MessageRole.USER, sourceRevision,
        ).map { it.toDomain() }

    // 직접 할당한 UUID라 Spring Data save()는 merge로 처리되어 INSERT 전에 SELECT가 한 번 더 나간다.
    // 추가만 하는 데이터이므로 persist로 바로 INSERT한다(호출부 트랜잭션 안에서 실행된다).
    override fun append(message: Message): Message {
        // UPDATE의 행 잠금은 커밋까지 유지된다. USER·ASSISTANT 모두 같은 기록 순번을 사용한다.
        val sequence = jdbc.queryForObject("""
            UPDATE conversation.record_days SET stream_sequence=stream_sequence+1
            WHERE user_id=? AND id=? RETURNING stream_sequence
            """, Long::class.java, message.userId.value, message.recordDayId.value) ?: error("기록이 없습니다")
        val saved = message.copy(streamSequence=sequence)
        entityManager.persist(saved.toEntity())
        return saved
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
        sourceSequence = sourceSequence,
        recordDayId = RecordDayId(recordDayId),
        clientMessageId = clientMessageId,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        createdAt = createdAt,
        deletedAt = deletedAt,
        streamSequence = streamSequence,
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
        sourceSequence = sourceSequence,
        recordDayId = recordDayId.value,
        clientMessageId = clientMessageId,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        createdAt = createdAt,
        deletedAt = deletedAt,
        streamSequence = streamSequence,
    )
