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
    fun latestClosed(userId: UserId): RecordDay?
    fun list(userId: UserId, displayDate: LocalDate?, limit: Int): List<RecordDay>
    fun insert(day: RecordDay)
    fun update(day: RecordDay)
    fun claimDueClosure(now: Instant): RecordDay?
    // 닫힌 미전달 기록 중 선점되지 않았거나 expiredBefore 이전에 선점된 하나를 now로 선점한다(SKIP LOCKED).
    fun claimDelivery(now: Instant, expiredBefore: Instant): RecordDay?
    // 등록이 커밋된 뒤에만 호출한다. 등록은 멱등이므로 어느 전달 시도가 확인해도 결과가 같다.
    fun acknowledgeDelivery(userId: UserId, id: RecordDayId): Boolean
}
