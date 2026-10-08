package com.moeum.platform.llm.journal.claude

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.claude")
data class ClaudeProperties(
    val apiKey: String,
    val model: String = "claude-haiku-4-5-20251001",
    val baseUrl: String = "https://api.anthropic.com",
    val apiVersion: String = "2023-06-01",
    val maxTokens: Int = 2_000,
    val connectionTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 15_000,
)
