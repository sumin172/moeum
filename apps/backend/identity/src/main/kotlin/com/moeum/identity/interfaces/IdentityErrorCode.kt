package com.moeum.identity.interfaces

enum class IdentityErrorCode(val code: String, val description: String) {
    INVALID_GOOGLE_TOKEN("IDENTITY_INVALID_GOOGLE_TOKEN", "Google ID 토큰 검증 실패"),
}
