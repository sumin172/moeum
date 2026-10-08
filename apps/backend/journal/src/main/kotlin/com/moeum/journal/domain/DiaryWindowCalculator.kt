package com.moeum.journal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class DiaryWindow(
    val diaryDate: LocalDate,
    val windowStart: Instant,
    val windowEnd: Instant,
)

// occurredAt이 속하는 diary day를 계산한다. "하루"의 경계는 자정이 아니라 generationTime이다 — 지역 시각이
// generationTime 이후면 그 날짜, 이전이면 전날 날짜에 속한다(shifted-day bucketing). timezone은 Conversation이
// 메시지 저장 시점에 이미 검증한 값이라 여기서 다시 검증하지 않는다.
fun calculateDiaryWindow(occurredAt: Instant, timezone: String, generationTime: LocalTime): DiaryWindow {
    val zoneId = ZoneId.of(timezone)
    val local = occurredAt.atZone(zoneId)

    val diaryDate = if (local.toLocalTime().isBefore(generationTime)) {
        local.toLocalDate().minusDays(1)
    } else {
        local.toLocalDate()
    }

    return DiaryWindow(
        diaryDate = diaryDate,
        windowStart = ZonedDateTime.of(diaryDate, generationTime, zoneId).toInstant(),
        windowEnd = ZonedDateTime.of(diaryDate.plusDays(1), generationTime, zoneId).toInstant(),
    )
}
