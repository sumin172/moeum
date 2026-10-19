package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Duration
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

    fun append(zone: ZoneId, now: Instant): RecordDay {
        check(closedAt == null) { "마감된 기록에는 메시지를 추가할 수 없습니다" }
        val nextRevision = sourceRevision + 1
        val scheduled = resolveClose(closeDate, closeTime, zone)
        val maximum = startAt.plus(MAX_RECORD_DURATION)
        val nextClose = if (scheduled <= now) now else minOf(scheduled, maximum)
        return copy(
            timezone = zone.id, sourceRevision = nextRevision,
            closeAt = nextClose,
        ).closeIfDue(now)
    }

    companion object {
        val DEFAULT_CLOSE_TIME: LocalTime = LocalTime.of(2, 0)
        val MAX_RECORD_DURATION: Duration = Duration.ofHours(36)

        fun create(userId: UserId, acceptedAt: Instant, zone: ZoneId, closeTime: LocalTime, startFloor: Instant? = null): RecordDay {
            var closeDate = acceptedAt.atZone(zone).toLocalDate()
            if (resolveClose(closeDate, closeTime, zone) <= acceptedAt) closeDate = closeDate.plusDays(1)
            val previousClose = resolveClose(closeDate.minusDays(1), closeTime, zone)
            val startAt = maxOf(previousClose, startFloor ?: previousClose)
            require(acceptedAt >= startAt) { "기존 기록 구간과 겹칩니다" }
            val closeAt = minOf(resolveClose(closeDate, closeTime, zone), startAt.plus(MAX_RECORD_DURATION))
            return RecordDay(
                id = RecordDayId.generate(), userId = userId,
                displayDate = if (closeTime < LocalTime.NOON) closeDate.minusDays(1) else closeDate,
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
