package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.domain.model.UserRecordState
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

/** 호출부 트랜잭션의 사용자 상태 → 기록 순서 잠금을 따른다. */
interface RecordDayRepository {
    fun lockState(userId: UserId): UserRecordState
    fun findState(userId: UserId): UserRecordState?
    fun saveState(state: UserRecordState)
    fun find(userId: UserId, id: RecordDayId, lock: Boolean = false): RecordDay?
    fun findOpen(userId: UserId, lock: Boolean = false): RecordDay?
    fun latestClosedBoundary(userId: UserId): Instant?
    fun list(userId: UserId, displayDate: LocalDate?, limit: Int): List<RecordDay>
    fun insert(day: RecordDay)
    fun update(day: RecordDay)
    fun claimDueClosure(now: Instant): RecordDay?
    fun claimDelivery(now: Instant, leaseExpiresAt: Instant): RecordDay?
    fun acknowledgeDelivery(userId: UserId, id: RecordDayId, version: Long): Boolean
}
