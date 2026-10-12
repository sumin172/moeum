package com.moeum.conversation.support

import com.moeum.conversation.application.command.ConversationQuotaService
import com.moeum.conversation.domain.ConversationUsage
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.identity.application.publicapi.ConversationAllowanceQuery
import java.time.Instant
import com.moeum.conversation.domain.ConversationUsageRepository
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.UserId
import java.time.Duration
import java.time.LocalDate

class InMemoryConversationUsageRepository : ConversationUsageRepository {
    val counts = mutableMapOf<Pair<UserId, LocalDate>, Int>()
    private val windows = mutableMapOf<UserId,ConversationUsage>()
    override fun find(userId: UserId) = windows[userId]
    override fun consume(userId: UserId, date: LocalDate, minute: Instant, limit: Int, requestsPerMinute: Int): Boolean {
        val old = windows[userId]
        val used = old?.takeIf { it.date==date }?.used ?: 0
        val rate = old?.takeIf { it.requestWindow==minute }?.requestCount ?: 0
        if (used>=limit || rate>=requestsPerMinute) return false
        windows[userId] = ConversationUsage(date,used+1,minute,rate+1)
        counts[userId to date] = used+1
        return true
    }
}

fun quotaService(limit: Int = 20, usage: ConversationUsageRepository = InMemoryConversationUsageRepository()): ConversationQuotaService =
    ConversationQuotaService(usage, { limit }, ConversationLimitProperties(),
        FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z")))

// 운영 기본값과 같은 정책(지터 20% 포함)
val testResponseJobProperties = ResponseJobProperties(
    maxAttempts = 3,
    backoff = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
    lease = Duration.ofMinutes(2),
)
