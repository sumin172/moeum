package com.moeum.journal.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface GenerationJobJpaRepository : JpaRepository<GenerationJobJpaEntity, UUID> {
    fun findByUserIdAndDiaryDate(userId: UUID, diaryDate: LocalDate): GenerationJobJpaEntity?
}
