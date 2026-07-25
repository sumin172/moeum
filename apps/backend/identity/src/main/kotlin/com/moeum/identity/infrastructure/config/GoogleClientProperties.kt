package com.moeum.identity.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.google")
data class GoogleClientProperties(
    val clientIds: List<String>,
)