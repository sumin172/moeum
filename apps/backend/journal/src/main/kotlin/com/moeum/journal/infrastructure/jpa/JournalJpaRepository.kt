package com.moeum.journal.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface JournalJpaRepository : JpaRepository<JournalJpaEntity, UUID> {
    fun findByUserIdAndDiaryDate(userId: UUID, diaryDate: LocalDate): JournalJpaEntity?
}
