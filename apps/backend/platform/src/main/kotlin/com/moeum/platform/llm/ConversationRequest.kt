package com.moeum.platform.llm

data class ConversationRequest(
    val messages: List<LlmMessage>,
    val systemPrompt: String? = null,
)
