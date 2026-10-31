package com.moeum.conversation.interfaces.dto

import com.moeum.conversation.application.query.ResponseState
import com.moeum.conversation.domain.model.Message
import java.time.Instant
import java.util.UUID

data class MessageResponse(
    val id: UUID,
    val role: String,
    val content: String,
    val occurredAt: Instant,
    val streamSequence: Long,
    // USER는 접수 순번, ASSISTANT는 그 응답이 답한 마지막 USER 순번
    val sourceSequence: Long,
) {
    companion object {
        fun from(message: Message): MessageResponse =
            MessageResponse(
                id = message.id.value,
                role = message.role.name,
                content = message.content,
                occurredAt = message.occurredAt,
                streamSequence = message.streamSequence,
                sourceSequence = message.sourceSequence,
            )
    }
}

// 기록의 AI 응답 상태. sourceSequence가 answeredSourceRevision보다 큰 USER가 아직 답하지 않은 입력이다.
data class ResponseStateResponse(
    // PENDING | PROCESSING | COMPLETED | FAILED, 정리된 작업이면 null — 자동 재시도 대기 중이면 PENDING
    val status: String?,
    // true면 POST /api/conversations/records/{recordDayId}/response-attempts로 다시 요청할 수 있다
    val retryable: Boolean,
    val answeredSourceRevision: Long,
) {
    companion object {
        fun from(state: ResponseState) =
            ResponseStateResponse(state.job?.state?.status?.name, state.retryable, state.answeredSourceRevision)
    }
}
