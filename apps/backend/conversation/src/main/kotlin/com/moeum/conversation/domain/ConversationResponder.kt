package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message
import java.util.UUID

// AI 대화 상대의 응답을 만든다. 프롬프트와 모델 선택은 구현(infrastructure/ai)이 소유한다.
interface ConversationResponder {
    // context: 작업의 원본 순번 상한 이하에서 제한해 조회한 대화 문맥. 실패하면 예외 — 호출부(응답 작업)가 재시도를 판단한다.
    fun respond(context: List<Message>): ConversationResponse
}

data class ConversationResponse(
    val generationId: UUID,
    val content: String,
    val model: String,
    val promptVersion: String,
)
