package com.moeum.conversation.application.command

import com.moeum.conversation.domain.*
import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.identity.application.publicapi.ConversationAllowanceQuery
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class ConversationQuotaService(
    private val usage: ConversationUsageRepository,
    private val records: RecordDayRepository,
    private val allowance: ConversationAllowanceQuery,
    private val limits: ConversationLimitProperties,
    private val clock: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun current(user: UserId): ConversationQuota = current(user, clock.now())
    private fun current(user: UserId, now: Instant): ConversationQuota {
        val limit = allowance.recordLimit(user)
        val record = records.findOpen(user)?.takeIf { now < it.closeAt }
        return snapshot(limit, record)
    }
    private fun snapshot(limit: Int, record: RecordDay?): ConversationQuota {
        val used = record?.sourceRevision?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        return ConversationQuota(limit, used, (limit-used).coerceAtLeast(0), record?.closeAt)
    }
    /** 사용자 상태·기록 잠금 아래 새 원본 접수 트랜잭션에서만 호출한다. */
    fun consume(user: UserId, record: RecordDay, now: Instant) {
        val limit = allowance.recordLimit(user)
        if (record.sourceRevision >= limit.toLong()) throw AiQuotaExceededException(snapshot(limit, record))
        val minute = now.truncatedTo(ChronoUnit.MINUTES)
        if (!usage.consume(user,minute,limits.acceptedMessagesPerMinute)) {
            throw ConversationRateExceededException("새 메시지 접수 속도를 초과했습니다")
        }
    }
}
