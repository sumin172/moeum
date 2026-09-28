package com.moeum.conversation.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "moment_sets", schema = "conversation")
class MomentSetJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "conversation_day_id", nullable = false)
    val conversationDayId: UUID,
    @Column(name = "source_revision", nullable = false)
    val sourceRevision: Long,
    @Column(name = "generation_id", nullable = false)
    val generationId: UUID,
    @Column(nullable = false)
    val model: String,
    @Column(name = "prompt_version", nullable = false)
    val promptVersion: String,
    @Column(name = "is_current", nullable = false)
    val isCurrent: Boolean = true,
    @Column(name = "superseded_at")
    val supersededAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
