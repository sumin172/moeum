package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface ConversationDayJpaRepository : JpaRepository<ConversationDayJpaEntity, UUID> {
    fun findByUserIdAndLocalDate(userId: UUID, localDate: LocalDate): ConversationDayJpaEntity?
}
