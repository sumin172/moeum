package com.moeum.platform.llm

import java.util.UUID

data class ConversationResponse(
    val generationId: UUID,
    val content: String,
    val model: String,
    val provider: String,
    val promptVersion: String,
)
