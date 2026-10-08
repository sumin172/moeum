package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message
import com.moeum.kernel.UserId
import java.util.UUID

// AI 대화 상대의 응답을 만든다. 프롬프트와 모델 선택은 구현(infrastructure/ai)이 소유한다.
interface ConversationResponder {
    // context: 그 하루의 대화 전체(발화 순서). 실패하면 예외 — 호출부(응답 작업)가 재시도를 판단한다.
    fun respond(userId: UserId, context: List<Message>): ConversationResponse
}

data class ConversationResponse(
    val generationId: UUID,
    val content: String,
    val model: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Int,
    val outputTokens: Int,
)
