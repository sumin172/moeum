package com.moeum.platform.web

enum class PlatformErrorCode(val code: String, val description: String) {
    UNAUTHORIZED("UNAUTHORIZED", "인증 실패 (JWT 누락/무효/만료)"),
    INTERNAL_ERROR("INTERNAL_ERROR", "처리되지 않은 예외"),
}
