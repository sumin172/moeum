package com.moeum.conversation.support

import com.moeum.conversation.application.command.ConversationQuotaService
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import java.time.Instant
import com.moeum.conversation.domain.ConversationUsageRepository
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.UserId
import java.time.Duration

class InMemoryConversationUsageRepository : ConversationUsageRepository {
    val counts = mutableMapOf<UserId, Int>()
    private val windows = mutableMapOf<UserId,Pair<Instant,Int>>()
    override fun consume(userId: UserId, minute: Instant, requestsPerMinute: Int): Boolean {
        val old = windows[userId]
        val rate = old?.takeIf { it.first==minute }?.second ?: 0
        if (rate>=requestsPerMinute) return false
        windows[userId] = minute to (rate+1)
        counts[userId] = (counts[userId] ?: 0)+1
        return true
    }
}

fun quotaService(limit: Int = 20, usage: ConversationUsageRepository = InMemoryConversationUsageRepository(),
                 records: InMemoryRecordDayRepository = InMemoryRecordDayRepository()): ConversationQuotaService =
    ConversationQuotaService(usage, records, { limit }, ConversationLimitProperties(),
        FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z")))

// 운영 기본값과 같은 정책(지터 20% 포함)
val testResponseJobProperties = ResponseJobProperties(
    maxAttempts = 3,
    backoff = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
    lease = Duration.ofMinutes(2),
)
