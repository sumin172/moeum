package com.moeum.conversation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

// 이상 사용(아주 긴 메시지, 비정상적으로 긴 하루 대화)으로 LLM 비용이 튀지 않게 하는 상한. 잠정값.
@ConfigurationProperties(prefix = "moeum.conversation.limits")
data class ConversationLimitProperties(
    // 메시지 하나의 최대 글자 수. 넘으면 저장을 거부한다(400).
    val maxMessageLength: Int = 4_000,
    // AI 응답 컨텍스트로 보낼 하루 대화의 최대 글자 수(대략 수천 토큰). 넘으면 오래된 메시지부터 빼고 최근 대화만 보낸다.
    // 하루 전체 재전송은 비용이 메시지 수의 제곱으로 늘어나므로 그 상한 — 정상 사용에서는 걸리지 않는 수준이다.
    val contextCharBudget: Int = 20_000,
)
