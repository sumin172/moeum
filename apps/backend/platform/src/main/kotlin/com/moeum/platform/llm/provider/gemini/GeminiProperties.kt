package com.moeum.platform.llm.provider.gemini

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import org.springframework.boot.context.properties.ConfigurationProperties

// 모델은 용도별 라우팅(moeum.llm.routes.*.model)에서 정한다. 여기는 연결 정보만.
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@ConfigurationProperties(prefix = "moeum.gemini")
data class GeminiProperties(
    // 쓰지 않는 역할에서는 비어 있어도 된다. 일기처럼 Gemini를 라우트로 쓰는 역할은 기동 시 검사한다.
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val connectionTimeoutMs: Int = 3_000,
    // 일기 생성처럼 출력이 긴 호출도 들어오므로 대화 응답 기준보다 넉넉하게 잡는다
    val readTimeoutMs: Int = 60_000,
)
