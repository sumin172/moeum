package com.moeum.conversation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

// 메시지 접수 상한과 AI 대화 문맥의 실행 상한.
@ConfigurationProperties(prefix = "moeum.conversation.limits")
data class ConversationLimitProperties(
    // 메시지 하나의 최대 글자 수. 넘으면 저장을 거부한다(400).
    val maxMessageLength: Int = 4_000,
    // 한 응답 호출의 글자 예산. 미응답 입력을 오래된 순으로 먼저 배정하고 남는 예산에 최근 과거 문맥을 담는다.
    // 메시지 상한 이상이어야 첫 미응답 입력이 항상 들어간다.
    val contextCharBudget: Int = 20_000,
    // 한 응답 호출의 메시지 수 상한. 글자 예산과 같이 미응답 입력에 먼저 배정한다.
    val contextMessageLimit: Int = 100,
    val acceptedMessagesPerMinute: Int = 60,
) {
    init { require(maxMessageLength in 1..contextCharBudget && contextMessageLimit > 0 && acceptedMessagesPerMinute > 0) }
}
