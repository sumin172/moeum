package com.moeum.conversation.infrastructure.jpa


import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageJpaRepository : JpaRepository<MessageJpaEntity, UUID> {
    fun findByUserIdAndId(userId: UUID, id: UUID): MessageJpaEntity?

    fun findByUserIdAndClientMessageId(userId: UUID, clientMessageId: UUID): MessageJpaEntity?

    fun findByUserIdAndRecordDayIdAndIdGreaterThanOrderByIdAsc(
        userId: UUID,
        recordDayId: UUID,
        id: UUID,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdOrderByIdDesc(
        userId: UUID,
        recordDayId: UUID,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdOrderByIdAsc(userId: UUID, recordDayId: UUID): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdAndRoleAndSourceSequenceLessThanEqualOrderByOccurredAtAscSourceSequenceAsc(
        userId: UUID, recordDayId: UUID, role: com.moeum.conversation.domain.model.MessageRole, sourceRevision: Long,
    ): List<MessageJpaEntity>
}
