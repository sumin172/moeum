package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface MessageJpaRepository : JpaRepository<MessageJpaEntity, UUID> {
    fun findByUserIdAndClientMessageId(userId: UUID, clientMessageId: UUID): MessageJpaEntity?

    fun findByUserIdAndDayDateAndIdGreaterThanOrderByIdAsc(
        userId: UUID,
        dayDate: LocalDate,
        id: UUID,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndDayDateOrderByIdDesc(
        userId: UUID,
        dayDate: LocalDate,
        pageable: Pageable,
    ): List<MessageJpaEntity>

    fun findByUserIdAndDayDateOrderByIdAsc(userId: UUID, dayDate: LocalDate): List<MessageJpaEntity>

    fun findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from: Instant, to: Instant): List<MessageJpaEntity>
}
