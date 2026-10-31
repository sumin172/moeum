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

class LlmConversationResponderTest {

    private class CapturingLlmClient : LlmClient {
        val requiredPurposes = mutableListOf<String>()
        override fun requireRoute(purpose: String) { requiredPurposes += purpose }
        var request: LlmRequest? = null
        override fun generate(request: LlmRequest): LlmResult {
            this.request = request
            return LlmResult("많이 힘드셨겠어요.", "gemini-2.5-flash")
        }
    }

    @Test
    fun `생성 시 대화 응답 용도의 라우트와 인증만 요구한다`() {
        val llmClient = CapturingLlmClient()
        LlmConversationResponder(llmClient, ConversationLimitProperties())
        assertThat(llmClient.requiredPurposes).containsExactly(CONVERSATION_RESPONSE_PURPOSE)
    }

    @Test
    fun `대화 응답 용도로 과거 문맥 뒤에 이번 답변 대상을 역할 그대로 넘긴다`() {
        val llmClient = CapturingLlmClient()
        val userId = UserId.generate()
        val day = com.moeum.kernel.RecordDayId.generate()
        val question = userMessage(userId, "오늘 피곤했어", Instant.parse("2026-08-08T10:00:00Z"), day)
        val reply = Message.assistantMessage(
            MessageId.generate(), userId, "무슨 일 있었어요?", Instant.parse("2026-08-08T10:00:05Z"),
            day, sourceSequence=1,
        )
        val next = userMessage(userId, "야근했어", Instant.parse("2026-08-08T10:01:00Z"), day, sourceSequence = 2)
        val more = userMessage(userId, "밥도 못 먹었어", Instant.parse("2026-08-08T10:01:10Z"), day, sourceSequence = 3)

        val response = LlmConversationResponder(llmClient, ConversationLimitProperties()).respond(listOf(question, reply), listOf(next, more))

        val sent = llmClient.request!!
        assertThat(sent.purpose).isEqualTo(CONVERSATION_RESPONSE_PURPOSE)
        assertThat(sent.systemPrompt).contains("일상 기록")
        assertThat(sent.messages).containsExactly(
            LlmMessage(LlmRole.USER, "오늘 피곤했어"),
            LlmMessage(LlmRole.ASSISTANT, "무슨 일 있었어요?"),
            LlmMessage(LlmRole.USER, "야근했어"),
            LlmMessage(LlmRole.USER, "밥도 못 먹었어"),
        )
        assertThat(response.content).isEqualTo("많이 힘드셨겠어요.")
    }

    @Test
    fun `답변 대상은 줄이지 않고 남는 예산으로만 과거 문맥을 줄인다`() {
        val llmClient = CapturingLlmClient()
        val history = listOf(user("a".repeat(10), 0), assistant("b".repeat(10), 0))
        val targets = listOf(user("c".repeat(10), 1), user("d".repeat(10), 2))

        LlmConversationResponder(llmClient, ConversationLimitProperties(maxMessageLength = 10, contextCharBudget = 30))
            .respond(history, targets)

        assertThat(llmClient.request!!.messages.map { it.content.first() }).containsExactly('c', 'd')
    }

    private val day = com.moeum.kernel.RecordDayId.generate()
    private val owner = UserId.generate()
    private fun user(content: String, minute: Int) =
        userMessage(owner, content, Instant.parse("2026-08-08T10:00:00Z").plusSeconds(minute * 60L), day)
    private fun assistant(content: String, minute: Int) = Message.assistantMessage(
        MessageId.generate(), owner, content, Instant.parse("2026-08-08T10:00:30Z").plusSeconds(minute * 60L),
        day, sourceSequence=1,
    )

    @Test
    fun `과거 문맥이 예산 안이면 그대로 보낸다`() {
        val context = listOf(user("가".repeat(10), 0), assistant("나".repeat(10), 0), user("다".repeat(10), 1))

        assertThat(fitHistory(context, budgetChars = 30)).isEqualTo(context)
    }

    @Test
    fun `예산을 넘으면 오래된 과거 문맥부터 빼되, 사용자 발화로 시작하게 한다`() {
        val context = listOf(
            user("a".repeat(10), 0), assistant("b".repeat(10), 0),
            user("c".repeat(10), 1), assistant("d".repeat(10), 1),
            user("e".repeat(10), 2),
        )

        // 최근부터 30자: e, d, c → 잘린 앞이 사용자 발화(c)라 그대로
        assertThat(fitHistory(context, budgetChars = 30).map { it.content.first() }).containsExactly('c', 'd', 'e')
        // 최근부터 20자: e, d → 앞이 AI 응답(d)이라 빼고 e만
        assertThat(fitHistory(context, budgetChars = 20).map { it.content.first() }).containsExactly('e')
        assertThat(fitHistory(context, budgetChars = 0)).isEmpty()
    }

    @Test
    fun `미응답 입력은 오래된 순으로 메시지 수·글자 예산 안까지 고르고 첫 입력은 항상 포함한다`() {
        val unanswered = listOf(user("a".repeat(10), 0), user("b".repeat(10), 1), user("c".repeat(10), 2))

        assertThat(com.moeum.conversation.domain.model.selectResponseTargets(unanswered, 20, 100).map { it.content.first() })
            .containsExactly('a', 'b')
        assertThat(com.moeum.conversation.domain.model.selectResponseTargets(unanswered, 5, 100).map { it.content.first() })
            .containsExactly('a')
        assertThat(com.moeum.conversation.domain.model.selectResponseTargets(unanswered, 100, 2).map { it.content.first() })
            .containsExactly('a', 'b')
    }
}
