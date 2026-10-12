package com.moeum.journal.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface JournalJpaRepository : JpaRepository<JournalJpaEntity, UUID> {
    fun findByUserIdAndDisplayDateOrderByIdAsc(userId: UUID, displayDate: LocalDate): List<JournalJpaEntity>
    fun findByUserIdAndRecordDayId(userId: UUID, recordDayId: UUID): JournalJpaEntity?
}
