package com.moeum.conversation.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity
@Table(name = "day_preferences", schema = "conversation")
class DayPreferenceJpaEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Column(name = "day_start_time", nullable = false)
    val dayStartTime: LocalTime,
    @Column(name = "pending_day_start_time")
    val pendingDayStartTime: LocalTime? = null,
    @Column(name = "pending_effective_from")
    val pendingEffectiveFrom: LocalDate? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
