package com.moeum.conversation.interfaces

enum class ConversationErrorCode(val code: String, val description: String) {
    INVALID_REQUEST("CONVERSATION_INVALID_REQUEST", "요청 값 검증 실패 (content 공백·최대 길이 초과, timezone 파싱 실패)"),
    MESSAGE_NOT_FOUND("CONVERSATION_MESSAGE_NOT_FOUND", "메시지가 없거나 응답을 요청할 수 없는 메시지"),
    RESPONSE_ALREADY_COMPLETED("CONVERSATION_RESPONSE_ALREADY_COMPLETED", "이미 AI 응답이 생성된 메시지"),
    QUOTA_EXCEEDED("CONVERSATION_QUOTA_EXCEEDED", "오늘 AI 응답 요청 한도 초과"),
}
