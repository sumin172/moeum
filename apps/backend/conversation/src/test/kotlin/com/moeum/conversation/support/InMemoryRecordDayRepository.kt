package com.moeum.conversation.support
import com.moeum.conversation.domain.RecordDayRepository
import com.moeum.conversation.domain.model.*
import com.moeum.kernel.*
import java.time.*
class InMemoryRecordDayRepository : RecordDayRepository {
    val days = linkedMapOf<RecordDayId, RecordDay>()
    val states = mutableMapOf<UserId, UserRecordState>()
    override fun lockState(userId: UserId) = states.getOrPut(userId) { UserRecordState(userId) }
    override fun findState(userId: UserId) = states[userId]
    override fun saveState(state: UserRecordState) { states[state.userId] = state }
    override fun find(userId: UserId, id: RecordDayId, lock: Boolean) = days[id]?.takeIf { it.userId == userId }
    override fun findOpen(userId: UserId, lock: Boolean) = days.values.firstOrNull { it.userId == userId && it.closedAt == null }
    override fun latestClosedBoundary(userId: UserId) =
        days.values.filter { it.userId == userId }.mapNotNull { it.closedAt }.maxOrNull()
    override fun list(userId: UserId, displayDate: LocalDate?, limit: Int) =
        days.values.filter { it.userId == userId && (displayDate == null || it.displayDate == displayDate) }.sortedByDescending { it.startAt }.take(limit)
    override fun insert(day: RecordDay) { days[day.id] = day }
    override fun update(day: RecordDay) { days[day.id] = day }
    override fun claimDueClosure(now: Instant) = days.values.firstOrNull { it.closedAt == null && it.closeAt <= now }
    override fun claimDelivery(now: Instant, leaseExpiresAt: Instant): RecordDay? {
        val day = days.values.firstOrNull { it.closedAt != null && !it.deliveryAcknowledged && (it.deliveryLeaseExpiresAt == null || it.deliveryLeaseExpiresAt <= now) } ?: return null
        return day.copy(deliveryVersion = day.deliveryVersion + 1, deliveryLeaseExpiresAt = leaseExpiresAt).also { days[it.id] = it }
    }
    override fun acknowledgeDelivery(userId: UserId, id: RecordDayId, version: Long): Boolean {
        val day = find(userId,id,false) ?: return false
        if(day.deliveryAcknowledged || day.deliveryVersion != version) return false
        days[id] = day.copy(deliveryAcknowledged = true, deliveryLeaseExpiresAt = null)
        return true
    }
}
