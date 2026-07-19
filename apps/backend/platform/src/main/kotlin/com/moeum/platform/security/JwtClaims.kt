package com.moeum.platform.security

import com.moeum.kernel.UserId
import java.time.Instant

data class JwtClaims(
    val userId: UserId,
    val subscriptionTier: String? = null,
    val issuedAt: Instant,
    val expiresAt: Instant,
)
