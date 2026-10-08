package com.moeum.journal.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "diary_preferences", schema = "journal")
class DiaryPreferenceJpaEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Column(name = "generation_time", nullable = false)
    val generationTime: LocalTime,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
