package com.moeum.conversation.support
import com.moeum.conversation.domain.RecordDayRepository
import com.moeum.conversation.domain.model.*
import com.moeum.kernel.*
import java.time.*
class InMemoryRecordDayRepository : RecordDayRepository {
    val days = linkedMapOf<RecordDayId, RecordDay>()
    val states = mutableMapOf<UserId, UserRecordState>()
    // 전달 상태는 도메인 모델이 아니라 저장소가 소유한다.
    private val deliveryClaimedAt = mutableMapOf<RecordDayId, Instant>()
    private val deliveryAcknowledged = mutableSetOf<RecordDayId>()
    override fun lockState(userId: UserId) = states.getOrPut(userId) { UserRecordState(userId) }
    override fun findState(userId: UserId) = states[userId]
    override fun saveState(state: UserRecordState) { states[state.userId] = state }
    override fun find(userId: UserId, id: RecordDayId, lock: Boolean) = days[id]?.takeIf { it.userId == userId }
    override fun findOpen(userId: UserId, lock: Boolean) = days.values.firstOrNull { it.userId == userId && it.closedAt == null }
    override fun latestClosed(userId: UserId) =
        days.values.filter { it.userId == userId && it.closedAt != null }.maxByOrNull { it.startAt }
    override fun list(userId: UserId, displayDate: LocalDate?, limit: Int) =
        days.values.filter { it.userId == userId && (displayDate == null || it.displayDate == displayDate) }.sortedByDescending { it.startAt }.take(limit)
    override fun insert(day: RecordDay) { days[day.id] = day }
    override fun update(day: RecordDay) { days[day.id] = day }
    override fun claimDueClosure(now: Instant) = days.values.firstOrNull { it.closedAt == null && it.closeAt <= now }
    override fun claimDelivery(now: Instant, expiredBefore: Instant): RecordDay? {
        val day = days.values.firstOrNull { it.closedAt != null && it.id !in deliveryAcknowledged &&
            deliveryClaimedAt[it.id].let { claimed -> claimed == null || claimed <= expiredBefore } } ?: return null
        deliveryClaimedAt[day.id] = now
        return day
    }
    override fun acknowledgeDelivery(userId: UserId, id: RecordDayId): Boolean {
        val day = find(userId,id,false) ?: return false
        return deliveryAcknowledged.add(day.id)
    }
}
