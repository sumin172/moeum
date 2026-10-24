package com.moeum.conversation.domain

import com.moeum.kernel.UserId
import java.time.Instant
interface ConversationUsageRepository {
    fun consume(userId: UserId, minute: Instant, requestsPerMinute: Int): Boolean
}
