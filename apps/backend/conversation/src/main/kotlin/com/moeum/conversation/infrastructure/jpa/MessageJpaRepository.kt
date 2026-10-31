package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.MessageRole
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageJpaRepository : JpaRepository<MessageJpaEntity, UUID> {
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

    fun findFirstByUserIdAndRecordDayIdAndRoleOrderBySourceSequenceDesc(
        userId: UUID, recordDayId: UUID, role: MessageRole,
    ): MessageJpaEntity?

    fun findByUserIdAndRecordDayIdAndRoleAndSourceSequenceGreaterThanAndSourceSequenceLessThanEqualOrderBySourceSequenceAsc(
        userId: UUID, recordDayId: UUID, role: MessageRole, answered: Long, observed: Long, pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndRecordDayIdAndSourceSequenceLessThanEqualAndStreamSequenceLessThanEqualOrderByStreamSequenceAsc(
        userId: UUID, recordDayId: UUID, sourceRevision: Long, streamSequence: Long,
    ): List<MessageJpaEntity>
}
