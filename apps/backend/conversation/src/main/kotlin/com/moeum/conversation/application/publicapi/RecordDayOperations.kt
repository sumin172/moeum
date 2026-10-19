package com.moeum.conversation.application.publicapi

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

/** Conversation의 내부 계약. 닫힌 기록의 입력은 잠금 없이 읽는다. */
interface RecordDayOperations {
    fun closeNext(): Boolean
    fun claimDelivery(): RecordDelivery?
    fun acknowledge(delivery: RecordDelivery): Boolean
    fun readJournalInput(userId: UserId, recordDayId: RecordDayId): JournalInput
}
data class RecordDelivery(
    val recordDayId: RecordDayId,
    val userId: UserId,
    val closedAt: Instant,
    val version: Long,
)
data class JournalInput(val displayDate: LocalDate, val messages: List<MessageSnapshot>)
data class MessageSnapshot(val role: String, val content: String, val occurredAt: Instant)
