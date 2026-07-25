package com.moeum.platform.llm.journal

data class JournalGenerationRequest(
    val moments: List<MomentSnapshot>,
    val localDate: String,
)
