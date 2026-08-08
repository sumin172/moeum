package com.moeum.conversation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.conversation.ai")
data class AiQuotaProperties(
    val dailyMessageLimit: Int = 20,
)
