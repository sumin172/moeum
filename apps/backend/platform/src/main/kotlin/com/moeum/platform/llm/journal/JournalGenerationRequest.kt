package com.moeum.platform.llm.journal

// rawTranscript는 ConversationActivityQuery.findMessages(userId, diaryDate)로 조회한 그 하루의 원본 대화 그대로.
data class JournalGenerationRequest(
    val rawTranscript: String,
    val localDate: String,
)
