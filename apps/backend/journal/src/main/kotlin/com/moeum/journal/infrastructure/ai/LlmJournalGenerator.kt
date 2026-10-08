package com.moeum.journal.infrastructure.ai

import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.journal.domain.GeneratedJournal
import com.moeum.journal.domain.JournalGenerator
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmResponseFormat
import com.moeum.platform.llm.LlmRole
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

// moeum.llm.routes.journal-generation으로 provider·모델·토큰 상한이 정해진다(Gemini ↔ Claude 전환도 설정으로).
const val JOURNAL_GENERATION_PURPOSE = "journal-generation"

// 프롬프트를 바꾸면 버전을 올린다(생성 메타데이터·호출 원장에 남아 품질 비교의 기준이 된다).
// v2: 대화 원본 앞에 날짜를 붙임(v1은 날짜를 넘겨받고도 쓰지 않았다)
private const val PROMPT_VERSION = "v2"

// JSON 객체만 응답하게 한다. Gemini는 응답 형식 강제 옵션을 함께 쓰고, Claude는 이 지시에만 의존한다.
private const val SYSTEM_PROMPT =
    "당신은 사용자의 하루 대화 원본을 바탕으로 1인칭 일기를 쓰는 작가입니다. " +
        "대화에 실제로 등장한 내용만 사용하고, 없는 내용을 지어내지 마세요. " +
        "오직 JSON 객체만 응답하세요. {\"title\": string, \"body\": string} 형태입니다. " +
        "title은 그날을 대표하는 짧은 한국어 제목, body는 자연스러운 한국어 1인칭 일기 서술입니다."

class JournalGenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Component
class LlmJournalGenerator(
    private val llmClient: LlmClient,
    private val jsonMapper: JsonMapper,
) : JournalGenerator {

    override fun generate(userId: UserId, diaryDate: LocalDate, messages: List<MessageSnapshot>): GeneratedJournal {
        val result = llmClient.generate(
            LlmRequest(
                purpose = JOURNAL_GENERATION_PURPOSE,
                systemPrompt = SYSTEM_PROMPT,
                messages = listOf(LlmMessage(LlmRole.USER, buildTranscript(diaryDate, messages))),
                promptVersion = PROMPT_VERSION,
                responseFormat = LlmResponseFormat.JSON,
                userId = userId,
            ),
        )

        val payload = runCatching { jsonMapper.readValue(result.text, JournalPayload::class.java) }
            .getOrElse { e -> throw JournalGenerationException("일기 생성 응답 JSON 파싱 실패: ${result.text}", e) }

        return GeneratedJournal(
            generationId = result.generationId,
            title = payload.title,
            body = payload.body,
            model = result.model,
            provider = result.provider,
            promptVersion = result.promptVersion,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
        )
    }
}

private fun buildTranscript(diaryDate: LocalDate, messages: List<MessageSnapshot>): String =
    "날짜: $diaryDate\n" + messages.joinToString("\n") { "[${it.occurredAt}] ${it.role}: ${it.content}" }

private data class JournalPayload(val title: String, val body: String)
