package com.moeum.conversation.infrastructure.ai

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
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

        val response = LlmConversationResponder(llmClient, ConversationLimitProperties()).respond(userId, listOf(question, reply))

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

    private val day = LocalDate.of(2026, 8, 8)
    private val owner = UserId.generate()
    private fun user(content: String, minute: Int) =
        userMessage(owner, content, Instant.parse("2026-08-08T10:00:00Z").plusSeconds(minute * 60L), day)
    private fun assistant(content: String, minute: Int) = Message.assistantMessage(
        MessageId.generate(), owner, content, Instant.parse("2026-08-08T10:00:30Z").plusSeconds(minute * 60L),
        "Asia/Seoul", day, day, UUID.randomUUID(), "m", "v1", 1, 1,
    )

    @Test
    fun `컨텍스트가 예산 안이면 하루 대화를 그대로 보낸다`() {
        val context = listOf(user("가".repeat(10), 0), assistant("나".repeat(10), 0), user("다".repeat(10), 1))

        assertThat(fitToBudget(context, budgetChars = 30)).isEqualTo(context)
    }

    @Test
    fun `예산을 넘으면 오래된 메시지부터 빼되, 사용자 발화로 시작하게 한다`() {
        val context = listOf(
            user("a".repeat(10), 0), assistant("b".repeat(10), 0),
            user("c".repeat(10), 1), assistant("d".repeat(10), 1),
            user("e".repeat(10), 2),
        )

        // 최근부터 30자: e, d, c → 잘린 앞이 사용자 발화(c)라 그대로
        assertThat(fitToBudget(context, budgetChars = 30).map { it.content.first() }).containsExactly('c', 'd', 'e')
        // 최근부터 20자: e, d → 앞이 AI 응답(d)이라 빼고 e만
        assertThat(fitToBudget(context, budgetChars = 20).map { it.content.first() }).containsExactly('e')
    }

    @Test
    fun `가장 최근 메시지 하나가 예산보다 길어도 그 메시지는 보낸다`() {
        val context = listOf(user("짧은 말", 0), user("x".repeat(100), 1))

        assertThat(fitToBudget(context, budgetChars = 50).map { it.content }).containsExactly("x".repeat(100))
    }
}
