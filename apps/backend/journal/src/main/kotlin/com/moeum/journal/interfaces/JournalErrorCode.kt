package com.moeum.journal.interfaces

enum class JournalErrorCode(val code: String, val description: String) {
    INVALID_REQUEST("JOURNAL_INVALID_REQUEST", "요청 값 검증 실패 (제목·본문 공백)"),
    NOT_FOUND("JOURNAL_NOT_FOUND", "그 하루의 일기(또는 생성할 일기)가 없음"),
    VERSION_CONFLICT("JOURNAL_VERSION_CONFLICT", "다른 기기에서 먼저 바뀐 일기 — 최신 일기를 다시 받아서 시도"),
    EDIT_NOT_ALLOWED("JOURNAL_EDIT_NOT_ALLOWED", "일기 수정은 구독 기능"),
    GENERATION_NOT_RETRYABLE("JOURNAL_GENERATION_NOT_RETRYABLE", "이미 생성된 일기"),
}
