package com.moeum.conversation.application.publicapi.events

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class MomentsPreparedV1(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "MomentsPrepared",
    val eventVersion: Int = 1,
    val occurredAt: Instant,
    val correlationId: UUID = UUID.randomUUID(),
    val causationId: UUID? = null,
    val conversationDayId: UUID,
    val userId: UUID,
    val localDate: LocalDate,
    val sourceRevision: Long,
    val momentSetId: UUID,
    val moments: List<MomentSnapshotV1>,
)

data class MomentSnapshotV1(
    val type: String,
    val summary: String,
    val emotion: String?,
    val confidence: Float?,
    val occurredAt: Instant?,
)
