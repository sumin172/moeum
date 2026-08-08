package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MessageJpaRepository : JpaRepository<MessageJpaEntity, UUID> {
    fun findByUserIdAndClientMessageId(userId: UUID, clientMessageId: UUID): MessageJpaEntity?

    fun findByConversationDayIdAndIdGreaterThanOrderByIdAsc(
        conversationDayId: UUID,
        id: UUID,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByConversationDayIdOrderByIdDesc(
        conversationDayId: UUID,
        pageable: Pageable,
    ): List<MessageJpaEntity>
}
