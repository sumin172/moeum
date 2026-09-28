package com.moeum.platform.llm.moment

import java.time.Instant

data class ExtractedMoment(
    val type: String,
    val summary: String,
    val emotion: String? = null,
    val confidence: Float? = null,
    val occurredAt: Instant? = null,
)
