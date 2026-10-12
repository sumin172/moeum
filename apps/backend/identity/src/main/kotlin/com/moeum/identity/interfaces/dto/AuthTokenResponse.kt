package com.moeum.identity.interfaces.dto

import com.moeum.identity.application.command.AuthTokens
import java.time.Instant

// 모바일·웹 모두 body로 받는다. 웹은 BFF(Next.js)가 refreshToken을 httpOnly 쿠키로 감싸 브라우저에 노출하지 않는다.
data class AuthTokenResponse(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    // refresh할 때마다 새 값으로 바뀐다 — 이전 값은 다시 쓰면 세션이 폐기된다
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
) {
    companion object {
        fun from(tokens: AuthTokens): AuthTokenResponse =
            AuthTokenResponse(tokens.accessToken, tokens.accessTokenExpiresAt, tokens.refreshToken, tokens.refreshTokenExpiresAt)
    }
}
