package com.moeum.identity.domain.model

import java.security.MessageDigest
import java.util.HexFormat

// HMAC으로 세션 ID와 비밀값의 진위를 검증한 refresh token. DB에는 비밀값의 해시만 저장한다.
data class RefreshToken(val sessionId: AuthSessionId, val secret: String, val value: String) {

    fun hash(): String = hashOf(secret)

    companion object {
        private fun hashOf(secret: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
    }
}
