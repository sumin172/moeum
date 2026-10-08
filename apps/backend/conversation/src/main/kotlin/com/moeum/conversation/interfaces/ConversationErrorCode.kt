package com.moeum.conversation.interfaces

enum class ConversationErrorCode(val code: String, val description: String) {
    INVALID_REQUEST("CONVERSATION_INVALID_REQUEST", "요청 값 검증 실패 (content 공백 또는 timezone 파싱 실패)"),
}
