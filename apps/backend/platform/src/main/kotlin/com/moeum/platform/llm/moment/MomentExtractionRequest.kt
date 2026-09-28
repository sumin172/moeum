package com.moeum.platform.llm.moment

// conversation.messages를 그 자리에서 읽어 만든 하루치 대화 원본 텍스트.
data class MomentExtractionRequest(
    val rawTranscript: String,
    val localDate: String,
)
