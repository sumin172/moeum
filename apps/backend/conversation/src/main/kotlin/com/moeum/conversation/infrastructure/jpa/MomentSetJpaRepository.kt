package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MomentSetJpaRepository : JpaRepository<MomentSetJpaEntity, UUID> {
    fun findByConversationDayIdAndSourceRevision(conversationDayId: UUID, sourceRevision: Long): MomentSetJpaEntity?
}
