package com.moeum.identity.domain.model

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

// refresh token 값 "<세션 ID>.<비밀값>". 비밀값은 DB에 남기지 않고 해시만 저장한다.
data class RefreshToken(val sessionId: AuthSessionId, val secret: String) {

    val value: String get() = "${sessionId.value}.$secret"

    fun hash(): String = hashOf(secret)

    companion object {
        private val random = SecureRandom()

        fun issue(sessionId: AuthSessionId): RefreshToken {
            val bytes = ByteArray(32).also(random::nextBytes)
            return RefreshToken(sessionId, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

        // 형식이 틀리면 null — 호출부는 "유효하지 않은 토큰"으로 다룬다.
        fun parse(value: String): RefreshToken? {
            val separator = value.indexOf('.')
            if (separator <= 0 || separator == value.lastIndex) return null
            val sessionId = runCatching { UUID.fromString(value.substring(0, separator)) }.getOrNull() ?: return null
            return RefreshToken(AuthSessionId(sessionId), value.substring(separator + 1))
        }

        private fun hashOf(secret: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()))
    }
}
