package com.moeum.identity.interfaces

enum class IdentityErrorCode(val code: String, val description: String) {
    INVALID_GOOGLE_TOKEN("IDENTITY_INVALID_GOOGLE_TOKEN", "Google ID 토큰 검증 실패"),
    INVALID_REFRESH_TOKEN("IDENTITY_INVALID_REFRESH_TOKEN", "refresh token이 없거나 만료·폐기됨 — 다시 로그인해야 한다"),
    REFRESH_TOKEN_ROTATED("IDENTITY_REFRESH_TOKEN_ROTATED", "동시 요청으로 이미 교체된 refresh token — 먼저 받은 새 토큰을 쓴다"),
    USER_DELETED("IDENTITY_USER_DELETED", "탈퇴한 사용자"),
}
