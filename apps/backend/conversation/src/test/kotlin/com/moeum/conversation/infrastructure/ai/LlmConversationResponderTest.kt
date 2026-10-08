package com.moeum.conversation.infrastructure.ai

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmResult
import com.moeum.platform.llm.LlmRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LlmConversationResponderTest {

    private class CapturingLlmClient : LlmClient {
        var request: LlmRequest? = null
        override fun generate(request: LlmRequest): LlmResult {
            this.request = request
            return LlmResult(UUID.randomUUID(), "많이 힘드셨겠어요.", "google", "gemini-2.5-flash", request.promptVersion, 30, 12)
        }
    }

    @Test
    fun `대화 응답 용도로 하루 대화를 역할 그대로 넘기고 결과를 생성 메타데이터와 함께 돌려준다`() {
        val llmClient = CapturingLlmClient()
        val userId = UserId.generate()
        val day = LocalDate.of(2026, 8, 8)
        val question = userMessage(userId, "오늘 피곤했어", Instant.parse("2026-08-08T10:00:00Z"), day)
        val reply = Message.assistantMessage(
            MessageId.generate(), userId, "무슨 일 있었어요?", Instant.parse("2026-08-08T10:00:05Z"),
            "Asia/Seoul", day, day, UUID.randomUUID(), "m", "v1", 1, 1,
        )

        val response = LlmConversationResponder(llmClient).respond(userId, listOf(question, reply))

        val sent = llmClient.request!!
        assertThat(sent.purpose).isEqualTo(CONVERSATION_RESPONSE_PURPOSE)
        assertThat(sent.userId).isEqualTo(userId)
        assertThat(sent.systemPrompt).contains("일상 기록")
        assertThat(sent.messages).containsExactly(
            LlmMessage(LlmRole.USER, "오늘 피곤했어"),
            LlmMessage(LlmRole.ASSISTANT, "무슨 일 있었어요?"),
        )
        assertThat(response.content).isEqualTo("많이 힘드셨겠어요.")
        assertThat(response.provider).isEqualTo("google")
        assertThat(response.promptVersion).isEqualTo(sent.promptVersion)
    }
}
