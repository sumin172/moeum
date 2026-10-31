package com.moeum.platform.llm.provider.cloudflare

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import org.springframework.boot.context.properties.ConfigurationProperties

@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@ConfigurationProperties(prefix = "moeum.cloudflare")
data class CloudflareProperties(
    val accountId: String = "",
    val apiToken: String = "",
    val baseUrl: String = "https://api.cloudflare.com",
    val connectionTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 60_000,
)
