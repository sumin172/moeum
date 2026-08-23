package com.moeum.conversation.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.conversation.day-close")
data class ConversationDayCloseProperties(
    val batchSize: Int = 200,
)
