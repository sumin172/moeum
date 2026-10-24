package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.conversation.domain.InvalidConversationRequestException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 메시지를 컬렉션으로 보유하지 않는다. 열린 기록의 귀속·마감 예약과 전달 상태만 소유한다. */
data class RecordDay(
    val id: RecordDayId,
    val userId: UserId,
    val displayDate: LocalDate,
    val startAt: Instant,
    val closeDate: LocalDate,
    val closeTime: LocalTime,
    val timezone: String,
    val closeAt: Instant,
    val sourceRevision: Long = 0,
    val closedAt: Instant? = null,
    val deliveryVersion: Long = 0,
    val deliveryLeaseExpiresAt: Instant? = null,
    val deliveryAcknowledged: Boolean = false,
) {
    fun closeIfDue(now: Instant): RecordDay =
        if (closedAt == null && now >= closeAt) copy(closedAt = closeAt) else this

    fun prepareForMessage(zone: ZoneId, now: Instant): RecordDay {
        val due = closeIfDue(now)
        if (due.closedAt != null) return due
        val scheduled = resolveClose(closeDate, closeTime, zone)
        // 새 장소에서 마감이 지났으면 기존 입력의 경계를 지금 확정한다. 새 메시지는 다음 기록에 속한다.
        return if (scheduled <= now) copy(closeAt = now, closedAt = now)
        else copy(timezone = zone.id, closeAt = scheduled)
    }

    fun append(): RecordDay {
        check(closedAt == null) { "마감된 기록에는 메시지를 추가할 수 없습니다" }
        return copy(sourceRevision = sourceRevision + 1)
    }

    companion object {
        val DEFAULT_CLOSE_TIME: LocalTime = LocalTime.of(2, 0)
        fun validateCloseTime(closeTime: LocalTime, eveningStart: LocalTime, morningEnd: LocalTime) {
            if (closeTime < eveningStart && closeTime > morningEnd) {
                throw InvalidConversationRequestException("마감 시각은 ${eveningStart}부터 다음 날 ${morningEnd}까지 설정할 수 있습니다")
            }
        }

        fun create(userId: UserId, acceptedAt: Instant, zone: ZoneId, closeTime: LocalTime,
                   eveningStart: LocalTime, morningEnd: LocalTime, previous: RecordDay? = null): RecordDay {
            validateCloseTime(closeTime, eveningStart, morningEnd)
            var closeDate = acceptedAt.atZone(zone).toLocalDate()
            if (resolveClose(closeDate, closeTime, zone) <= acceptedAt) closeDate = closeDate.plusDays(1)
            var deferredForNextDay = false
            if (previous != null && previous.closeTime != closeTime) {
                val earliestDisplayDate = previous.displayDate.plusDays(1)
                val earliestCloseDate = if (closeTime <= morningEnd) earliestDisplayDate.plusDays(1) else earliestDisplayDate
                if (closeDate < earliestCloseDate) {
                    closeDate = earliestCloseDate
                    deferredForNextDay = true
                }
            }
            val previousClose = resolveClose(closeDate.minusDays(1), closeTime, zone)
            val startAt = if (deferredForNextDay) previous!!.closedAt!! else maxOf(previousClose, previous?.closedAt ?: previousClose)
            require(acceptedAt >= startAt) { "기존 기록 구간과 겹칩니다" }
            val closeAt = resolveClose(closeDate, closeTime, zone)
            return RecordDay(
                id = RecordDayId.generate(), userId = userId,
                displayDate = if (closeTime <= morningEnd) closeDate.minusDays(1) else closeDate,
                startAt = startAt, closeDate = closeDate, closeTime = closeTime, timezone = zone.id,
                closeAt = closeAt,
            )
        }

        /** gap은 최초 유효 시각, overlap은 뒤쪽 offset을 사용한다. */
        fun resolveClose(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
            val local = LocalDateTime.of(date, time)
            val offsets = zone.rules.getValidOffsets(local)
            return if (offsets.isEmpty()) zone.rules.getTransition(local)!!.dateTimeAfter.atZone(zone).toInstant()
            else local.toInstant(offsets.last())
        }
    }
}

data class UserRecordState(
    val userId: UserId,
    val closeTime: LocalTime = RecordDay.DEFAULT_CLOSE_TIME,
)
