package com.moeum.platform.security.jwt

import com.moeum.kernel.UserId
import java.time.Instant

data class JwtClaims(
    val userId: UserId,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
