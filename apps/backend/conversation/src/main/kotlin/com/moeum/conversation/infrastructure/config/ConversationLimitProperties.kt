package com.moeum.conversation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

// 메시지 접수와 AI 대화 문맥의 개발 기본 상한.
@ConfigurationProperties(prefix = "moeum.conversation.limits")
data class ConversationLimitProperties(
    // 메시지 하나의 최대 글자 수. 넘으면 저장을 거부한다(400).
    val maxMessageLength: Int = 4_000,
    // 작업의 원본 순번 이하에서 조회한 최근 문맥의 글자 예산. 초과하면 오래된 메시지부터 제외한다.
    val contextCharBudget: Int = 20_000,
    val contextMessageLimit: Int = 100,
    val acceptedMessagesPerMinute: Int = 60,
) {
    init { require(maxMessageLength > 0 && contextCharBudget >= maxMessageLength && contextMessageLimit > 0 && acceptedMessagesPerMinute > 0) }
}
