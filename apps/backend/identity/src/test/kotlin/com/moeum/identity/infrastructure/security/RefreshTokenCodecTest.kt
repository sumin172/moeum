package com.moeum.identity.infrastructure.security

import com.moeum.identity.domain.model.AuthSessionId
import com.moeum.identity.infrastructure.config.AuthSessionProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class RefreshTokenCodecTest {
    private val properties = AuthSessionProperties(signingSecret = "test-refresh-signing-secret-at-least-32-bytes")
    private val codec = RefreshTokenCodec(properties)

    @Test
    fun `발급 토큰은 동일 키를 사용하는 다른 인스턴스에서도 검증된다`() {
        val id = AuthSessionId.generate()
        val token = codec.issue(id)
        assertThat(RefreshTokenCodec(properties).verify(token.value)).isEqualTo(token)
        assertThat(codec.issue(id).value).isNotEqualTo(token.value)
    }

    @Test
    fun `세션 ID 비밀값 서명 변조와 다른 키 및 잘못된 형식은 거부한다`() {
        val token = codec.issue(AuthSessionId.generate())
        val parts = token.value.split('.')
        val secret = (if (parts[1][0] == 'A') "B" else "A") + parts[1].drop(1)
        val signature = (if (parts[2][0] == 'A') "B" else "A") + parts[2].drop(1)
        listOf("${AuthSessionId.generate().value}.${parts[1]}.${parts[2]}",
            "${parts[0]}.$secret.${parts[2]}", "${parts[0]}.${parts[1]}.$signature",
            "${parts[0]}.${parts[1]}", "garbage", token.value + ".extra").forEach {
            assertThat(codec.verify(it)).isNull()
        }
        val other = RefreshTokenCodec(AuthSessionProperties(signingSecret = "other-refresh-signing-secret-at-least-32-bytes"))
        assertThat(other.verify(token.value)).isNull()
    }

    @Test
    fun `누락되거나 짧은 키는 기동 시 거부한다`() {
        listOf("", "short").forEach { secret ->
            assertThatThrownBy { RefreshTokenCodec(AuthSessionProperties(signingSecret = secret)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
