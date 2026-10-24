package com.moeum.conversation.domain

import java.time.Instant

data class ConversationQuota(val limit: Int, val used: Int, val remaining: Int, val resetAt: Instant?)
