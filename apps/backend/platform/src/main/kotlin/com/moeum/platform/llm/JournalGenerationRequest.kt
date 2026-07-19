package com.moeum.platform.llm

data class JournalGenerationRequest(
    val moments: List<MomentSnapshot>,
    val localDate: String,
)
