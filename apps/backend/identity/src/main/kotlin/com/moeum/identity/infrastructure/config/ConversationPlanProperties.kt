package com.moeum.identity.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.identity.conversation")
data class ConversationPlanProperties(val freeDailyLimit: Int = 20, val premiumDailyLimit: Int = 100) {
    init { require(freeDailyLimit > 0 && premiumDailyLimit > freeDailyLimit) }
}
