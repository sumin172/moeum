package com.moeum.platform.llm.journal

// rawTranscript는 ConversationJournalSourceQuery로 조회한 원본 대화 그대로.
data class JournalGenerationRequest(
    val rawTranscript: String,
    val localDate: String,
)
