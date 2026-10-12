package com.moeum.conversation.domain

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

data class ConversationUsage(val date: LocalDate, val used: Int, val requestWindow: Instant, val requestCount: Int)
interface ConversationUsageRepository {
    fun consume(userId: UserId, date: LocalDate, minute: Instant, limit: Int, requestsPerMinute: Int): Boolean
    fun find(userId: UserId): ConversationUsage?
}
