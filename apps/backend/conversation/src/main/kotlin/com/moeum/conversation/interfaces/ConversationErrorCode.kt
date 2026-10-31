package com.moeum.conversation.interfaces

enum class ConversationErrorCode(val code: String, val description: String) {
    INVALID_REQUEST("CONVERSATION_INVALID_REQUEST", "요청 값 검증 실패 (content 공백·최대 길이 초과, timezone 파싱 실패)"),
    RESPONSE_NOT_FOUND("CONVERSATION_RESPONSE_NOT_FOUND", "기록에 응답 작업이 없거나 재시도 기간이 끝남"),
    RESPONSE_ALREADY_COMPLETED("CONVERSATION_RESPONSE_ALREADY_COMPLETED", "재시도할 실패 응답이 없는 기록"),
}
