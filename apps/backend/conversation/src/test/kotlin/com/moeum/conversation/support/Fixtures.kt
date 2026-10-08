package com.moeum.conversation.support

import com.moeum.conversation.application.command.AiQuotaGuard
import com.moeum.conversation.domain.AiUsageRepository
import com.moeum.conversation.infrastructure.config.AiQuotaProperties
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.UserId
import java.time.Duration
import java.time.LocalDate

class InMemoryAiUsageRepository : AiUsageRepository {
    val counts = mutableMapOf<Pair<UserId, LocalDate>, Int>()
    override fun recordAttempt(userId: UserId, date: LocalDate): Int =
        counts.merge(userId to date, 1, Int::plus)!!
}

fun quotaGuard(limit: Int = 20, usage: AiUsageRepository = InMemoryAiUsageRepository()): AiQuotaGuard =
    AiQuotaGuard(usage, AiQuotaProperties(dailyMessageLimit = limit))

// 운영 기본값과 같은 정책(지터 20% 포함)
val testResponseJobProperties = ResponseJobProperties(
    maxAttempts = 3,
    backoff = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
    lease = Duration.ofMinutes(2),
)
