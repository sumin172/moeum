package com.moeum.conversation.application.publicapi.events

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ConversationDayClosedV1(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String = "ConversationDayClosed",
    val eventVersion: Int = 1,
    val occurredAt: Instant,
    val correlationId: UUID = UUID.randomUUID(),
    val causationId: UUID? = null,
    val conversationDayId: UUID,
    val userId: UUID,
    val localDate: LocalDate,
)
