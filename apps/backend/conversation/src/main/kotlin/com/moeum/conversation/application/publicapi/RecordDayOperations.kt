package com.moeum.conversation.application.publicapi

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Conversation의 상태 소유권을 보존하는 내부 전달/입력 계약. 각 쓰기 호출은 독립 트랜잭션이다. */
interface RecordDayOperations {
    fun closeNext(): Boolean
    fun claimDelivery(): RecordDelivery?
    fun acknowledge(delivery: RecordDelivery): Boolean
    fun freezeInput(userId: UserId, recordDayId: RecordDayId): FrozenRecordInput
}
data class RecordDelivery(
    val recordDayId: RecordDayId,
    val userId: UserId,
    val displayDate: LocalDate,
    val closedAt: Instant,
    val version: Long,
)
data class FrozenRecordInput(val sourceRevision: Long, val messages: List<MessageSnapshot>)
data class MessageSnapshot(val id: UUID, val role: String, val content: String, val occurredAt: Instant)
