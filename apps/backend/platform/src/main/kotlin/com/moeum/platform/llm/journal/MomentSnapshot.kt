package com.moeum.platform.llm.journal

data class MomentSnapshot(
    val type: String,
    val summary: String,
    val emotion: String? = null,
)
