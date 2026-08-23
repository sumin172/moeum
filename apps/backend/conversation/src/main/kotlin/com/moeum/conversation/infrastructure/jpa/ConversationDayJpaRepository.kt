package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.ConversationDayStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ConversationDayJpaRepository : JpaRepository<ConversationDayJpaEntity, UUID> {
    fun findByUserIdAndLocalDate(userId: UUID, localDate: LocalDate): ConversationDayJpaEntity?

    fun findByStatusAndClosesAtLessThanEqual(
        status: ConversationDayStatus,
        closesAt: Instant,
        pageable: Pageable,
    ): List<ConversationDayJpaEntity>

    // WHERE 절의 openStatus가 원자적 선점(claim) 조건 — 동시에 호출돼도 정확히 하나만 1행을 갱신한다.
    // version도 함께 증가시켜, 이 UPDATE 이후 도착한 메시지 저장(@Version 낙관적 락 기반 save)이
    // 이미 마감된 row를 그대로 덮어써 재오픈시키는 레이스를 방지한다.
    @Transactional
    @Modifying
    @Query(
        "UPDATE ConversationDayJpaEntity d SET d.status = :closedStatus, d.closedAt = :closedAt, d.version = d.version + 1 " +
                "WHERE d.entityId = :id AND d.status = :openStatus",
    )
    fun closeIfOpen(
        @Param("id") id: UUID,
        @Param("closedAt") closedAt: Instant,
        @Param("openStatus") openStatus: ConversationDayStatus,
        @Param("closedStatus") closedStatus: ConversationDayStatus,
    ): Int
}
