package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.model.GenerationJobStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface GenerationJobJpaRepository : JpaRepository<GenerationJobJpaEntity, UUID> {
    fun findByGenerationStatusAndScheduledAtLessThanEqual(
        generationStatus: GenerationJobStatus,
        scheduledAt: Instant,
    ): List<GenerationJobJpaEntity>

    // WHERE 절의 expectedStatus가 원자적 선점(claim) 조건 — 동시에 호출돼도 정확히 하나만 1행을 갱신한다.
    @Transactional
    @Modifying
    @Query(
        "UPDATE GenerationJobJpaEntity g SET g.generationStatus = :updatedStatus " +
            "WHERE g.id = :id AND g.generationStatus = :expectedStatus",
    )
    fun compareAndSetStatus(
        @Param("id") id: UUID,
        @Param("expectedStatus") expectedStatus: GenerationJobStatus,
        @Param("updatedStatus") updatedStatus: GenerationJobStatus,
    ): Int
}
