package com.moeum.conversation.application.command

import com.moeum.conversation.domain.*
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.identity.application.publicapi.ConversationAllowanceQuery
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Service
class ConversationQuotaService(
    private val usage: ConversationUsageRepository,
    private val allowance: ConversationAllowanceQuery,
    private val limits: ConversationLimitProperties,
    private val clock: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun current(user: UserId): ConversationQuota = current(user, clock.now())
    private fun current(user: UserId, now: Instant): ConversationQuota {
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        val limit = allowance.dailyLimit(user)
        val used = usage.find(user)?.takeIf { it.date == date }?.used ?: 0
        return ConversationQuota(limit, used, (limit-used).coerceAtLeast(0), date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
    }
    /** 새 원본 접수 트랜잭션 안에서만 호출한다. 중복·실패 재시도는 호출하지 않는다. */
    fun consume(user: UserId, now: Instant) {
        val limit = allowance.dailyLimit(user)
        val date = now.atZone(ZoneOffset.UTC).toLocalDate()
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        if (!usage.consume(user,date,minute,limit,limits.acceptedMessagesPerMinute)) {
            val snapshot = current(user,now)
            if (snapshot.remaining == 0) throw AiQuotaExceededException(snapshot)
            throw ConversationRateExceededException("새 메시지 접수 속도를 초과했습니다")
        }
    }
}
