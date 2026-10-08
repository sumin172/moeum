package com.moeum.journal.domain

import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.kernel.UserId
import java.time.LocalDate
import java.util.UUID

// 하루의 원본 대화로 일기 초안을 쓴다. 프롬프트·응답 해석·모델 선택은 구현(infrastructure/ai)이 소유한다.
interface JournalGenerator {
    // 실패하면 예외 — 호출부(일기 생성 작업)가 재시도를 판단한다.
    fun generate(userId: UserId, diaryDate: LocalDate, messages: List<MessageSnapshot>): GeneratedJournal
}

data class GeneratedJournal(
    val generationId: UUID,
    val title: String,
    // journals.content(JSONB)에 그대로 저장되는 JSON
    val content: String,
    val model: String,
    val provider: String,
    val promptVersion: String,
    val inputTokens: Int,
    val outputTokens: Int,
)
