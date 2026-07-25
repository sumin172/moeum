package com.moeum.platform.llm.journal

interface JournalGenerator {
    fun generate(request: JournalGenerationRequest): JournalGenerationResponse
}
