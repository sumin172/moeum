package com.moeum.conversation.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "moments", schema = "conversation")
class MomentJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "moment_set_id", nullable = false)
    val momentSetId: UUID,
    @Column(name = "conversation_day_id", nullable = false)
    val conversationDayId: UUID,
    @Column(nullable = false)
    val type: String,
    @Column(nullable = false)
    val summary: String,
    @Column
    val emotion: String? = null,
    @Column
    val confidence: Float? = null,
    @Column(name = "occurred_at")
    val occurredAt: Instant? = null,
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
)
