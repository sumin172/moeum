package com.moeum.integration.support

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.model.Message
import com.moeum.platform.llm.LlmException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 지정한 사용자 메시지를 답변 대상에 포함한 응답 시도만 실패시킨다. 다른 기록의 백그라운드 작업은 영향을 받지 않는다.
class ControllableConversationResponder : ConversationResponder {
    private val failuresRemaining = ConcurrentHashMap<UUID, AtomicInteger>()

    fun failNext(clientMessageId: UUID, times: Int) {
        require(times > 0)
        failuresRemaining[clientMessageId] = AtomicInteger(times)
    }

    fun reset() = failuresRemaining.clear()

    override fun respond(history: List<Message>, targets: List<Message>): ConversationResponse {
        val remaining = targets.firstNotNullOfOrNull { it.clientMessageId?.let(failuresRemaining::get) }
        if ((remaining?.getAndUpdate { if (it > 0) it - 1 else 0 } ?: 0) > 0) {
            throw LlmException("테스트용 실패")
        }
        return ConversationResponse(content = "테스트 응답입니다.")
    }
}
