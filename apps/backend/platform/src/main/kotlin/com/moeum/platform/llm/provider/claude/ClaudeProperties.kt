package com.moeum.platform.llm.provider.claude

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import org.springframework.boot.context.properties.ConfigurationProperties

// 모델·토큰 상한은 용도별 라우팅(moeum.llm.routes.*)에서 정한다. 여기는 연결 정보만.
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@ConfigurationProperties(prefix = "moeum.claude")
data class ClaudeProperties(
    val apiKey: String,
    val baseUrl: String = "https://api.anthropic.com",
    val apiVersion: String = "2023-06-01",
    val connectionTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 60_000,
)
