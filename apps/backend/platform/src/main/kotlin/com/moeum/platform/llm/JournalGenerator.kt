package com.moeum.platform.llm

interface JournalGenerator {
    fun generate(request: JournalGenerationRequest): JournalGenerationResponse
}
