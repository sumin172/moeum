package com.moeum.conversation.interfaces.dto

import java.time.Instant
import java.util.UUID

data class SaveMessageRequest(
    val clientMessageId: UUID,
    val content: String,
    val occurredAt: Instant,
    val timezone: String,
)
