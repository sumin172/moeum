package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.model.GenerationJobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "generation_jobs", schema = "journal")
class GenerationJobJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "journal_id")
    val journalId: UUID?,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "diary_date", nullable = false)
    val diaryDate: LocalDate,
    @Column(name = "scheduled_at", nullable = false)
    val scheduledAt: Instant,
    @Column(name = "generation_status", nullable = false)
    @Enumerated(EnumType.STRING)
    val generationStatus: GenerationJobStatus,
    @Column(name = "attempt_count", nullable = false)
    val attemptCount: Int,
    @Column
    val provider: String? = null,
    @Column
    val model: String? = null,
    @Column(name = "prompt_version")
    val promptVersion: String? = null,
    @Column(name = "input_tokens")
    val inputTokens: Int? = null,
    @Column(name = "output_tokens")
    val outputTokens: Int? = null,
    @Column(name = "generation_id")
    val generationId: UUID? = null,
    @Column(name = "generated_at")
    val generatedAt: Instant? = null,
    @Column(name = "error_code")
    val errorCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
