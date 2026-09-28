package com.moeum.platform.llm.moment

import java.util.UUID

data class MomentExtractionResponse(
    val generationId: UUID,
    val moments: List<ExtractedMoment>,
    val model: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Int,
    val outputTokens: Int,
)
