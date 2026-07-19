package com.moeum.platform.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.jwt")
data class JwtProperties(
    val secret: String,
    val expirationSeconds: Long = 3600,
)
