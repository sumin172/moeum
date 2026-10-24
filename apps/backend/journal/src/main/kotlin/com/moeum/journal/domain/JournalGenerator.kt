package com.moeum.journal.domain

import com.moeum.conversation.application.publicapi.MessageSnapshot
import java.time.LocalDate

// 하루의 원본 대화로 일기를 생성한다. 프롬프트·응답 해석·모델 선택은 구현(infrastructure/ai)이 소유한다.
interface JournalGenerator {
    // 실패하면 예외 — 호출부(일기 생성 작업)가 재시도를 판단한다.
    fun generate(displayDate: LocalDate, messages: List<MessageSnapshot>): GeneratedJournal
}

data class GeneratedJournal(
    val title: String,
    val body: String,
)
