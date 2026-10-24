package com.moeum.identity.infrastructure.security

import com.moeum.identity.domain.model.AuthSessionId
import com.moeum.identity.domain.model.RefreshToken
import com.moeum.identity.infrastructure.config.AuthSessionProperties
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConditionalOnRole(ExecutionRole.API)
@Component
class RefreshTokenCodec(properties: AuthSessionProperties) {
    private val key = properties.signingSecret.toByteArray(Charsets.UTF_8).also {
        require(it.size >= 32) { "refresh token signing secret must be at least 32 bytes" }
    }.let { SecretKeySpec(it, "HmacSHA256") }
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val tokenFormat = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")

    fun issue(sessionId: AuthSessionId): RefreshToken {
        val secret = encoder.encodeToString(ByteArray(32).also(random::nextBytes))
        val payload = "${sessionId.value}.$secret"
        return RefreshToken(sessionId, secret, "$payload.${encoder.encodeToString(sign(payload))}")
    }

    fun verify(value: String): RefreshToken? {
        if (value.length != 124 || !tokenFormat.matches(value)) return null
        val (id, secret, signature) = value.split('.')
        val supplied = Base64.getUrlDecoder().decode(signature)
        // 비정규 Base64 표현도 거부해 같은 토큰의 여러 문자열 표현을 허용하지 않는다.
        if (encoder.encodeToString(supplied) != signature) return null
        if (!MessageDigest.isEqual(sign("$id.$secret"), supplied)) return null
        return RefreshToken(AuthSessionId(UUID.fromString(id)), secret, value)
    }

    // Mac은 thread-safe하지 않으므로 요청마다 생성한다. 용도 접두어로 서명 대상을 구분한다.
    private fun sign(payload: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key)
        doFinal("moeum-refresh-v1:$payload".toByteArray(Charsets.UTF_8))
    }
}
