
package com.moeum.journal.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "journals", schema = "journal")
class JournalJpaEntity(
    @Id val id: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "record_day_id", nullable = false) val recordDayId: UUID,
    @Column(name = "display_date", nullable = false) val displayDate: LocalDate,
    @Column(nullable = false) val title: String,
    @Column(nullable = false, columnDefinition = "text") val body: String,
    @Version @Column(nullable = false) var version: Long = 0,
    @Column(name = "source_revision", nullable = false) val sourceRevision: Long,
    @Column(name = "generation_id", nullable = false) val generationId: UUID,
    @Column(nullable = false) val model: String,
    @Column(name = "prompt_version", nullable = false) val promptVersion: String,
    @Column(name = "generated_at", nullable = false) val generatedAt: Instant,
)
