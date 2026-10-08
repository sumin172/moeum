package com.moeum.journal.infrastructure.ai

import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmResponseFormat
import com.moeum.platform.llm.LlmResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class LlmJournalGeneratorTest {

    private class ScriptedLlmClient(private val text: String) : LlmClient {
        var request: LlmRequest? = null
        override fun generate(request: LlmRequest): LlmResult {
            this.request = request
            return LlmResult(UUID.randomUUID(), text, "google", "gemini-2.5-flash", request.promptVersion, 300, 120)
        }
    }

    private val jsonMapper = JsonMapper.builder().build()
    private val userId = UserId.generate()
    private val diaryDate = LocalDate.of(2026, 8, 2)
    private val messages = listOf(
        MessageSnapshot(UUID.randomUUID(), "USER", "오늘 한강에서 산책했어", Instant.parse("2026-08-02T10:00:00Z")),
        MessageSnapshot(UUID.randomUUID(), "ASSISTANT", "좋았겠어요!", Instant.parse("2026-08-02T10:00:05Z")),
    )

    @Test
    fun `하루 원본을 날짜와 함께 JSON 응답 형식으로 요청하고, 제목과 본문을 나눠 돌려준다`() {
        val llmClient = ScriptedLlmClient("""{"title":"한강 산책","body":"오늘은 한강에서 산책을 했다."}""")

        val journal = LlmJournalGenerator(llmClient, jsonMapper).generate(userId, diaryDate, messages)

        val sent = llmClient.request!!
        assertThat(sent.purpose).isEqualTo(JOURNAL_GENERATION_PURPOSE)
        assertThat(sent.responseFormat).isEqualTo(LlmResponseFormat.JSON)
        assertThat(sent.userId).isEqualTo(userId)
        assertThat(sent.messages.single().content).startsWith("날짜: 2026-08-02").contains("USER: 오늘 한강에서 산책했어")
        assertThat(journal.title).isEqualTo("한강 산책")
        assertThat(journal.body).isEqualTo("오늘은 한강에서 산책을 했다.")
        assertThat(journal.outputTokens).isEqualTo(120)
    }

    @Test
    fun `응답이 약속한 JSON이 아니면 실패로 던져 재시도로 넘긴다`() {
        val generator = LlmJournalGenerator(ScriptedLlmClient("오늘은 좋은 하루였다"), jsonMapper)

        assertThatThrownBy { generator.generate(userId, diaryDate, messages) }
            .isInstanceOf(JournalGenerationException::class.java)
    }
}
