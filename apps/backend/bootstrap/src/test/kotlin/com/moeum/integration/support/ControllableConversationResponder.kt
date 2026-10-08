package com.moeum.integration.support

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.model.Message
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// 통합 테스트용 응답기. 기본은 항상 성공하고, failNext(n)으로 다음 n번 호출을 실패시킨다.
class ControllableConversationResponder : ConversationResponder {
    private val failuresRemaining = AtomicInteger(0)

    fun failNext(times: Int) = failuresRemaining.set(times)

    fun reset() = failuresRemaining.set(0)

    override fun respond(userId: UserId, context: List<Message>): ConversationResponse {
        if (failuresRemaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            throw LlmException("테스트용 실패")
        }
        return ConversationResponse(
            generationId = UUID.randomUUID(),
            content = "테스트 응답입니다.",
            model = "fake-model",
            provider = "fake",
            promptVersion = "test",
            inputTokens = 1,
            outputTokens = 1,
        )
    }
}
