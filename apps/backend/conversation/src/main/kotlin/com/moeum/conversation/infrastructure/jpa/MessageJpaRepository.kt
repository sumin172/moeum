package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.MessageResponseStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
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

    // WHERE 절의 expectedStatus가 원자적 선점(claim) 조건 — 동시에 호출돼도 정확히 하나만 1행을 갱신한다.
    @Transactional
    @Modifying
    @Query(
        "UPDATE MessageJpaEntity m SET m.responseStatus = :updatedStatus " +
            "WHERE m.id = :id AND m.responseStatus = :expectedStatus",
    )
    fun compareAndSetStatus(
        @Param("id") id: UUID,
        @Param("expectedStatus") expectedStatus: MessageResponseStatus,
        @Param("updatedStatus") updatedStatus: MessageResponseStatus,
    ): Int
}
