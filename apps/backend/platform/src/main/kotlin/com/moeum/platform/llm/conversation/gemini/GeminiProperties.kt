package com.moeum.platform.llm.conversation.gemini

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.gemini")
data class GeminiProperties(
    val apiKey: String,
    val model: String = "gemini-flash-latest",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val connectionTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 15_000,
)
