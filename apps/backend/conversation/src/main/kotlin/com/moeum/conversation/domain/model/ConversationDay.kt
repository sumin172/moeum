package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

data class ConversationDay(
    val id: ConversationDayId,
    val userId: UserId,
    val localDate: LocalDate,
    val timezone: String,
    val sourceRevision: Long,
    val version: Long,
    val openedAt: Instant,
) {
    fun withMessageAdded(timezone: String): ConversationDay =
        copy(sourceRevision = sourceRevision + 1, timezone = timezone)

    companion object {
        fun open(id: ConversationDayId, userId: UserId, localDate: LocalDate, timezone: String, now: Instant): ConversationDay =
            ConversationDay(
                id = id,
                userId = userId,
                localDate = localDate,
                timezone = timezone,
                sourceRevision = 0,
                version = 0,
                openedAt = now,
            )
    }
}
