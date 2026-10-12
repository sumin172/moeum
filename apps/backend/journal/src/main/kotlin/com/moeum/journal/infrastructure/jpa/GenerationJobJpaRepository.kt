package com.moeum.journal.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GenerationJobJpaRepository : JpaRepository<GenerationJobJpaEntity, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByUserIdAndId(userId: UUID, id: UUID): GenerationJobJpaEntity?
    fun findByUserIdAndRecordDayId(userId: UUID, recordDayId: UUID): GenerationJobJpaEntity?
}
