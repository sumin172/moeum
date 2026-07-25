package com.moeum.platform.llm.conversation

data class ConversationRequest(
    val messages: List<LlmMessage>,
    val systemPrompt: String? = null,
)
