package com.moeum.platform.llm.journal

interface JournalGenerator {
    fun generate(request: JournalGenerationRequest): JournalGenerationResponse
}

class JournalGenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
