package com.moeum.conversation.infrastructure.jpa


import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageJpaRepository : JpaRepository<MessageJpaEntity, UUID> {
    fun findByUserIdAndId(userId: UUID, id: UUID): MessageJpaEntity?

    fun findByUserIdAndClientMessageId(userId: UUID, clientMessageId: UUID): MessageJpaEntity?

    fun findByUserIdAndRecordDayIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(
        userId: UUID,
        recordDayId: UUID,
        streamSequence: Long,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdOrderByStreamSequenceDesc(
        userId: UUID,
        recordDayId: UUID,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdAndSourceSequenceLessThanEqualOrderBySourceSequenceDescRoleAsc(
        userId: UUID, recordDayId: UUID, sourceRevision: Long, pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdAndSourceSequenceLessThanEqualAndStreamSequenceLessThanEqualOrderByStreamSequenceAsc(
        userId: UUID, recordDayId: UUID, sourceRevision: Long, streamSequence: Long,
    ): List<MessageJpaEntity>
}
