package com.moeum.platform.llm.provider.gemini

import org.springframework.boot.context.properties.ConfigurationProperties

// 모델은 용도별 라우팅(moeum.llm.routes.*.model)에서 정한다. 여기는 연결 정보만.
@ConfigurationProperties(prefix = "moeum.gemini")
data class GeminiProperties(
    val apiKey: String,
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val connectionTimeoutMs: Int = 3_000,
    // 일기 생성처럼 출력이 긴 호출도 들어오므로 대화 응답 기준보다 넉넉하게 잡는다
    val readTimeoutMs: Int = 60_000,
)
