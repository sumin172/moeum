package com.moeum.conversation.domain.model

import com.moeum.conversation.domain.parseTimezone
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

enum class ConversationDayStatus { OPEN, CLOSED }

data class ConversationDay(
    val id: ConversationDayId,
    val userId: UserId,
    val localDate: LocalDate,
    val timezone: String,
    val status: ConversationDayStatus,
    val sourceRevision: Long,
    val version: Long,
    val openedAt: Instant,
    // 이 zone 기준 다음 자정에 해당하는 UTC Instant. 마감 배치가 "닫아도 되는지"를 인덱스 스캔만으로 판단하는 근거.
    val closesAt: Instant,
    val closedAt: Instant? = null,
) {
    fun withMessageAdded(timezone: String): ConversationDay =
        copy(sourceRevision = sourceRevision + 1, timezone = timezone, closesAt = computeClosesAt(localDate, timezone))

    companion object {
        fun open(id: ConversationDayId, userId: UserId, localDate: LocalDate, timezone: String, now: Instant): ConversationDay =
            ConversationDay(
                id = id,
                userId = userId,
                localDate = localDate,
                timezone = timezone,
                status = ConversationDayStatus.OPEN,
                sourceRevision = 0,
                version = 0,
                openedAt = now,
                closesAt = computeClosesAt(localDate, timezone),
            )

        private fun computeClosesAt(localDate: LocalDate, timezone: String): Instant =
            localDate.plusDays(1).atStartOfDay(parseTimezone(timezone)).toInstant()
    }
}
