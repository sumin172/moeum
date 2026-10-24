package com.moeum.platform.security.jwt

import com.moeum.kernel.UserId
import java.time.Instant

interface JwtProvider {
    fun issue(userId: UserId): IssuedAccessToken
    fun parse(token: String): JwtClaims
}

data class IssuedAccessToken(val token: String, val expiresAt: Instant)

class InvalidJwtException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
