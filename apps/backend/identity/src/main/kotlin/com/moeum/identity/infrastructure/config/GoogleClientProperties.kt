package com.moeum.identity.infrastructure.config

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import org.springframework.boot.context.properties.ConfigurationProperties

@ConditionalOnRole(ExecutionRole.API)
@ConfigurationProperties(prefix = "moeum.google")
data class GoogleClientProperties(
    val clientIds: List<String>,
)