package com.moeum.platform.llm.journal

import java.util.UUID

data class JournalGenerationResponse(
    val generationId: UUID,
    val title: String,
    val content: String,
    val model: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Int,
    val outputTokens: Int,
)
