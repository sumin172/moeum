package com.moeum.platform.llm.moment

import com.moeum.platform.llm.conversation.LlmMessage

data class MomentExtractionRequest(
    val messages: List<LlmMessage>,
    val localDate: String,
)
