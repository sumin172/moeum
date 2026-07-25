package com.moeum.platform.security.jwt

import com.moeum.kernel.UserId

interface JwtProvider {
    fun issue(userId: UserId): String
    fun parse(token: String): JwtClaims
}

class InvalidJwtException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
