package com.moeum.platform.llm.moment

interface MomentExtractor {
    fun extract(request: MomentExtractionRequest): MomentExtractionResponse
}

class MomentExtractionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
