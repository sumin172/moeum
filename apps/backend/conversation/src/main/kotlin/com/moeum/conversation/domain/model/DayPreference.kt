package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// 사용자의 "하루" 경계. 대화 컨텍스트, 일기, quota가 모두 이 경계로 나뉜 같은 하루(dayDate)를 쓴다.
// 메시지의 dayDate는 저장 시점에 이 규칙으로 한 번 계산되고 이후 재계산하지 않는다.
data class DayPreference(
    val userId: UserId,
    // 지역 시각이 이 시각 이후면 그 날짜, 이전이면 전날 날짜에 속한다(자정이 아니라 이 시각에 하루가 바뀐다).
    val dayStartTime: LocalTime,
    // 변경 요청은 "다음 하루"부터 적용한다 — 진행 중인 하루가 중간에 늘거나 줄지 않고,
    // 이미 끝난 하루로 새 메시지가 다시 들어가지 않게 하기 위함.
    val pendingDayStartTime: LocalTime? = null,
    val pendingEffectiveFrom: LocalDate? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun dayDateOf(instant: Instant, zoneId: ZoneId): LocalDate {
        val local = instant.atZone(zoneId)
        val byCurrent = shiftedDate(local, dayStartTime)
        val effectiveFrom = pendingEffectiveFrom ?: return byCurrent
        if (byCurrent < effectiveFrom) return byCurrent
        // 새 기준의 첫날은 이전 기준의 경계에서 시작한다 — effectiveFrom보다 앞(이미 끝난 하루)으로 되돌리지 않는다.
        return maxOf(shiftedDate(local, pendingDayStartTime!!), effectiveFrom)
    }

    fun startOf(dayDate: LocalDate, zoneId: ZoneId): Instant =
        ZonedDateTime.of(dayDate, startTimeOf(dayDate), zoneId).toInstant()

    fun endOf(dayDate: LocalDate, zoneId: ZoneId): Instant = startOf(dayDate.plusDays(1), zoneId)

    // today는 현재 기준으로 계산한 오늘(dayDateOf(now)). 변경은 내일부터 적용된다.
    fun requestChange(newDayStartTime: LocalTime, today: LocalDate, now: Instant): DayPreference {
        val settled = settledAsOf(today)
        if (newDayStartTime == settled.dayStartTime) {
            return settled.copy(pendingDayStartTime = null, pendingEffectiveFrom = null, updatedAt = now)
        }
        return settled.copy(
            pendingDayStartTime = newDayStartTime,
            pendingEffectiveFrom = today.plusDays(1),
            updatedAt = now,
        )
    }

    private fun startTimeOf(dayDate: LocalDate): LocalTime {
        val effectiveFrom = pendingEffectiveFrom
        return if (effectiveFrom == null || dayDate <= effectiveFrom) dayStartTime else pendingDayStartTime!!
    }

    // 이미 적용이 시작된 예약 변경을 현재 값으로 접는다. 접고 나면 effectiveFrom 이전 날짜도 새 기준으로 계산되지만,
    // 그 날짜의 메시지는 dayDate가 이미 저장돼 있어 영향이 없다(며칠 늦게 도착하는 오프라인 메시지만 예외 — 감수).
    private fun settledAsOf(today: LocalDate): DayPreference {
        val effectiveFrom = pendingEffectiveFrom ?: return this
        if (effectiveFrom > today) return this
        return copy(dayStartTime = pendingDayStartTime!!, pendingDayStartTime = null, pendingEffectiveFrom = null)
    }

    companion object {
        // 실사용 패턴 확인 전 잠정값. 최종 확정은 docs/DEVELOPMENT_STAGES.md "구현하면서 결정" 참고.
        val DEFAULT_DAY_START_TIME: LocalTime = LocalTime.of(2, 0)

        fun default(userId: UserId, now: Instant): DayPreference =
            DayPreference(userId = userId, dayStartTime = DEFAULT_DAY_START_TIME, createdAt = now, updatedAt = now)
    }
}

private fun shiftedDate(local: ZonedDateTime, dayStartTime: LocalTime): LocalDate =
    if (local.toLocalTime().isBefore(dayStartTime)) local.toLocalDate().minusDays(1) else local.toLocalDate()
