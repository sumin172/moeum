package com.moeum.identity.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.identity.conversation")
data class ConversationPlanProperties(val freeRecordLimit: Int = 20, val premiumRecordLimit: Int = 100) {
    init { require(freeRecordLimit > 0 && premiumRecordLimit > freeRecordLimit) }
}
