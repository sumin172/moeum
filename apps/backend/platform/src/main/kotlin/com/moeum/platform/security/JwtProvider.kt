package com.moeum.platform.security

interface JwtProvider {
    fun issue(claims: JwtClaims): String
    fun parse(token: String): JwtClaims
}

class InvalidJwtException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
