package com.moeum.platform.web

enum class PlatformErrorCode(val code: String, val description: String) {
    UNAUTHORIZED("UNAUTHORIZED", "인증 실패 (JWT 누락/무효/만료)"),
    INVALID_REQUEST("INVALID_REQUEST", "요청 형식 오류 (body 필수 필드 누락·형식 불일치, 쿼리 파라미터 누락·타입 불일치)"),
    INTERNAL_ERROR("INTERNAL_ERROR", "처리되지 않은 예외"),
}
