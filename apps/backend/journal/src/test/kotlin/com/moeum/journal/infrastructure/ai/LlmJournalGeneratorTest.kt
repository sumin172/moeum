package com.moeum.journal.infrastructure.ai

import com.moeum.conversation.application.publicapi.MessageSnapshot
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

class LlmJournalGeneratorTest {

    private class ScriptedLlmClient(private val text: String) : LlmClient {
        var request: LlmRequest? = null
        override fun generate(request: LlmRequest): LlmResult {
            this.request = request
            return LlmResult(text, "gemini-2.5-flash")
        }
    }

    private val jsonMapper = JsonMapper.builder().build()
    private val diaryDate = LocalDate.of(2026, 8, 2)
    private val messages = listOf(
        MessageSnapshot("USER", "오늘 한강에서 산책했어", Instant.parse("2026-08-02T10:00:00Z")),
        MessageSnapshot("ASSISTANT", "좋았겠어요!", Instant.parse("2026-08-02T10:00:05Z")),
    )

    @Test
    fun `하루 원본을 날짜와 함께 JSON 응답 형식으로 요청하고, 제목과 본문을 나눠 돌려준다`() {
        val llmClient = ScriptedLlmClient("""{"title":"한강 산책","body":"오늘은 한강에서 산책을 했다."}""")

        val journal = LlmJournalGenerator(llmClient, jsonMapper).generate(diaryDate, messages)

        val sent = llmClient.request!!
        assertThat(sent.purpose).isEqualTo(JOURNAL_GENERATION_PURPOSE)
        assertThat(sent.responseFormat).isEqualTo(LlmResponseFormat.JSON)
        assertThat(sent.messages.single().content).startsWith("날짜: 2026-08-02").contains("USER: 오늘 한강에서 산책했어")
        assertThat(journal.title).isEqualTo("한강 산책")
        assertThat(journal.body).isEqualTo("오늘은 한강에서 산책을 했다.")
    }

    @Test
    fun `응답이 약속한 JSON이 아니면 실패로 던져 재시도로 넘긴다`() {
        val generator = LlmJournalGenerator(ScriptedLlmClient("오늘은 좋은 하루였다"), jsonMapper)

        assertThatThrownBy { generator.generate(diaryDate, messages) }
            .isInstanceOf(JournalGenerationException::class.java)
    }

    @Test
    fun `짧은 답변 부정 정정과 AI 질문을 역할과 순서 그대로 전달한다`() {
        val dialogue = listOf(
            "USER" to "오늘 회사에서 일이 있었어",
            "ASSISTANT" to "팀장님에게 칭찬받았어?",
            "USER" to "응, 처음이야",
            "ASSISTANT" to "발표를 잘해서였어?",
            "USER" to "아니, 발표가 아니라 보고서 때문이야",
            "USER" to "정정할게. 팀장님이 아니라 동료였어",
        )
        val client = ScriptedLlmClient("""{"title":"인정","body":"보고서로 동료에게 인정받았다."}""")
        LlmJournalGenerator(client, jsonMapper).generate(diaryDate, dialogue.mapIndexed { i, (role, content) ->
            MessageSnapshot(role, content, Instant.parse("2026-08-02T10:00:00Z").plusSeconds(i.toLong()))
        })
        val request = client.request!!
        assertThat(request.promptVersion).isEqualTo("v4")
        assertThat(request.systemPrompt).contains("USER 발화만", "ASSISTANT 발화는", "추측이나 제안 자체를 사실로 쓰지", "정정")
        val transcript = request.messages.single().content
        val positions = dialogue.map { (role, content) -> transcript.indexOf("$role: $content") }
        assertThat(positions).allMatch { it >= 0 }.isSorted()
    }

    @Test
    fun `공백 누락 과도한 길이의 생성 결과를 거부하고 응답 원문을 예외에 넣지 않는다`() {
        val invalid = listOf(
            mapOf("title" to " ", "body" to "민감한 원문"),
            mapOf("title" to "제목", "body" to "\n\t"),
            mapOf("title" to "제목"),
            mapOf("title" to null, "body" to "민감한 원문"),
            mapOf("title" to "가".repeat(MAX_GENERATED_TITLE_LENGTH + 1), "body" to "민감한 원문"),
            mapOf("title" to "제목", "body" to "가".repeat(MAX_GENERATED_BODY_LENGTH + 1)),
        )
        invalid.forEach { payload ->
            val generator = LlmJournalGenerator(ScriptedLlmClient(jsonMapper.writeValueAsString(payload)), jsonMapper)
            assertThatThrownBy { generator.generate(diaryDate, messages) }
                .isInstanceOf(JournalGenerationException::class.java)
                .hasMessageNotContaining("민감한 원문")
                .hasNoCause()
        }
        val client = ScriptedLlmClient(jsonMapper.writeValueAsString(mapOf(
            "title" to "가".repeat(MAX_GENERATED_TITLE_LENGTH), "body" to "나".repeat(MAX_GENERATED_BODY_LENGTH),
        )))
        assertThat(LlmJournalGenerator(client, jsonMapper).generate(diaryDate, messages).body)
            .hasSize(MAX_GENERATED_BODY_LENGTH)
    }

    @Test
    fun `사용자 발화 300개와 AI 문맥의 큰 입력도 중간을 자르지 않고 전달한다`() {
        // 개발 기본 구독 한도 100회와 메시지 4000자. AI 1000자는 시험 가정이며 공급자 상한이 아니다.
        // 이 시험은 입력 조립의 누락만 검증한다. 실제 토큰 한도·모델 품질·동시 메모리를 승인하지 않는다.
        val input = (0 until 300).flatMap { i ->
            val at = when (i / 100) {
                0 -> Instant.parse("2026-08-01T23:00:00Z")
                1 -> Instant.parse("2026-08-02T12:00:00Z")
                else -> Instant.parse("2026-08-03T00:00:00Z")
            }.plusSeconds((i % 100) * 30L)
            listOf(MessageSnapshot("USER", "USER-$i:".padEnd(4000, '원'), at),
                MessageSnapshot("ASSISTANT", "AI-$i:".padEnd(1000, '문'), at.plusSeconds(1)))
        }
        val client = ScriptedLlmClient("""{"title":"제목","body":"본문"}""")
        LlmJournalGenerator(client, jsonMapper).generate(diaryDate, input)
        val transcript = client.request!!.messages.single().content
        input.forEach { assertThat(transcript).contains("${it.role}: ${it.content}") }
        assertThat(transcript.length).isGreaterThan(1_490_000)
    }
}
