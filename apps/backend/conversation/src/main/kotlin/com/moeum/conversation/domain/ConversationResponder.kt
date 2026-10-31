package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message

// AI 대화 상대의 응답을 만든다. 프롬프트와 모델 선택은 구현(infrastructure/ai)이 소유한다.
interface ConversationResponder {
    // history: 이미 답한 범위의 최근 문맥. targets: 이번에 답할 미응답 USER 전체이며 예산 때문에 줄이지 않는다.
    // 실패하면 예외 — 호출부(응답 작업)가 재시도를 판단한다.
    fun respond(history: List<Message>, targets: List<Message>): ConversationResponse
}

data class ConversationResponse(
    val content: String,
)
