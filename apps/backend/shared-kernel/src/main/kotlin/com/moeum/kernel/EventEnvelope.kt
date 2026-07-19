package com.moeum.kernel

import java.time.Instant
import java.util.UUID

data class EventEnvelope(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String,
    val eventVersion: Int,
    val payload: Any,
    val occurredAt: Instant = Instant.now(),
)
