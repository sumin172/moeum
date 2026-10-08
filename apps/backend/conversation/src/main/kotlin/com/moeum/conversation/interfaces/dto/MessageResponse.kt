package com.moeum.conversation.interfaces.dto

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.ResponseJob
import java.time.Instant
import java.util.UUID

data class MessageResponse(
    val id: UUID,
    val role: String,
    val content: String,
    val occurredAt: Instant,
    // 아래 셋은 유저 메시지에만 값이 있다(AI 응답 생성 상태).
    // responseStatus: PENDING | PROCESSING | COMPLETED | FAILED — 자동 재시도 대기 중이면 PENDING
    val responseStatus: String?,
    // FAILED일 때만: QUOTA_EXCEEDED | GENERATION_FAILED
    val responseFailureReason: String?,
    // true면 POST /api/conversations/messages/{id}/response-attempts로 다시 요청할 수 있다
    val retryable: Boolean?,
) {
    companion object {
        fun from(message: Message, responseJob: ResponseJob?): MessageResponse =
            MessageResponse(
                id = message.id.value,
                role = message.role.name,
                content = message.content,
                occurredAt = message.occurredAt,
                responseStatus = responseJob?.state?.status?.name,
                responseFailureReason = responseJob?.failureReason?.name,
                retryable = responseJob?.retryable,
            )
    }
}
