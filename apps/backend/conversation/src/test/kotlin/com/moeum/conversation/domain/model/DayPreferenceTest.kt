package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class DayPreferenceTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val userId = UserId.generate()
    private val createdAt = Instant.parse("2026-07-01T00:00:00Z")

    private fun kst(dateTime: String): Instant = LocalDateTime.parse(dateTime).atZone(seoul).toInstant()

    private fun preference(dayStartTime: String) =
        DayPreference(userId = userId, dayStartTime = LocalTime.parse(dayStartTime), createdAt = createdAt, updatedAt = createdAt)

    // 하루 D 중에 변경을 요청한 상태 — 변경은 D+1부터 적용된다.
    private fun changed(from: String, to: String, requestedOn: LocalDate): DayPreference =
        preference(from).requestChange(LocalTime.parse(to), today = requestedOn, now = createdAt)

    @Test
    fun `하루 시작 시각 전이면 전날, 이후면 그날에 속한다`() {
        val pref = preference("02:00")

        assertThat(pref.dayDateOf(kst("2026-08-02T01:59"), seoul)).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(pref.dayDateOf(kst("2026-08-02T02:00"), seoul)).isEqualTo(LocalDate.of(2026, 8, 2))
        assertThat(pref.startOf(LocalDate.of(2026, 8, 2), seoul)).isEqualTo(kst("2026-08-02T02:00"))
        assertThat(pref.endOf(LocalDate.of(2026, 8, 2), seoul)).isEqualTo(kst("2026-08-03T02:00"))
    }

    @Test
    fun `자정을 넘겨도 하루 시작 시각 전까지는 같은 하루로 이어진다`() {
        val pref = preference("02:00")

        assertThat(pref.dayDateOf(kst("2026-08-01T23:50"), seoul))
            .isEqualTo(pref.dayDateOf(kst("2026-08-02T00:10"), seoul))
    }

    @Test
    fun `변경은 다음 하루부터 적용되고 오늘의 경계는 그대로다`() {
        val pref = changed(from = "02:00", to = "04:00", requestedOn = LocalDate.of(2026, 8, 1))

        assertThat(pref.dayStartTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(pref.pendingDayStartTime).isEqualTo(LocalTime.of(4, 0))
        assertThat(pref.pendingEffectiveFrom).isEqualTo(LocalDate.of(2026, 8, 2))
        // 8/1은 기존 기준대로 8/2 02:00에 끝난다
        assertThat(pref.endOf(LocalDate.of(2026, 8, 1), seoul)).isEqualTo(kst("2026-08-02T02:00"))
    }

    @Test
    fun `시작 시각을 늦추면 적용 첫날이 길어지고 이미 끝난 하루로 되돌아가지 않는다`() {
        val pref = changed(from = "02:00", to = "04:00", requestedOn = LocalDate.of(2026, 8, 1))
        val effectiveDay = LocalDate.of(2026, 8, 2)

        // 새 기준(04:00)만 보면 8/1이지만, 8/1은 이미 02:00에 끝났으므로 8/2에 속한다
        assertThat(pref.dayDateOf(kst("2026-08-02T03:00"), seoul)).isEqualTo(effectiveDay)
        assertThat(pref.startOf(effectiveDay, seoul)).isEqualTo(kst("2026-08-02T02:00"))
        assertThat(pref.endOf(effectiveDay, seoul)).isEqualTo(kst("2026-08-03T04:00"))
        assertThat(pref.dayDateOf(kst("2026-08-03T03:59"), seoul)).isEqualTo(effectiveDay)
        assertThat(pref.dayDateOf(kst("2026-08-03T04:00"), seoul)).isEqualTo(LocalDate.of(2026, 8, 3))
    }

    @Test
    fun `시작 시각을 앞당기면 진행 중인 하루는 기존 기준대로 끝나고 적용 첫날이 짧아진다`() {
        val pref = changed(from = "04:00", to = "02:00", requestedOn = LocalDate.of(2026, 8, 1))
        val effectiveDay = LocalDate.of(2026, 8, 2)

        // 8/1은 기존 기준대로 8/2 04:00까지 이어진다
        assertThat(pref.dayDateOf(kst("2026-08-02T03:00"), seoul)).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(pref.startOf(effectiveDay, seoul)).isEqualTo(kst("2026-08-02T04:00"))
        assertThat(pref.endOf(effectiveDay, seoul)).isEqualTo(kst("2026-08-03T02:00"))
        assertThat(pref.dayDateOf(kst("2026-08-03T01:59"), seoul)).isEqualTo(effectiveDay)
        assertThat(pref.dayDateOf(kst("2026-08-03T02:00"), seoul)).isEqualTo(LocalDate.of(2026, 8, 3))
    }

    @Test
    fun `변경 전후 어느 시각이든 계산된 하루의 구간 안에 들어간다`() {
        listOf("02:00" to "04:00", "04:00" to "02:00", "02:00" to "00:00").forEach { (from, to) ->
            val pref = changed(from = from, to = to, requestedOn = LocalDate.of(2026, 8, 1))
            var t = kst("2026-07-30T00:00")
            while (t < kst("2026-08-05T00:00")) {
                val day = pref.dayDateOf(t, seoul)
                assertThat(t).describedAs("$from→$to, t=$t, day=$day")
                    .isAfterOrEqualTo(pref.startOf(day, seoul))
                    .isBefore(pref.endOf(day, seoul))
                t = t.plus(Duration.ofMinutes(15))
            }
        }
    }

    @Test
    fun `현재 값과 같은 시각으로 다시 바꾸면 예약된 변경이 취소된다`() {
        val pref = changed(from = "02:00", to = "04:00", requestedOn = LocalDate.of(2026, 8, 1))
            .requestChange(LocalTime.of(2, 0), today = LocalDate.of(2026, 8, 1), now = createdAt)

        assertThat(pref.dayStartTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(pref.pendingDayStartTime).isNull()
        assertThat(pref.pendingEffectiveFrom).isNull()
    }

    @Test
    fun `적용이 시작된 예약 변경은 다음 변경 요청 때 현재 값으로 접힌다`() {
        val pref = changed(from = "02:00", to = "04:00", requestedOn = LocalDate.of(2026, 8, 1))
            .requestChange(LocalTime.of(3, 0), today = LocalDate.of(2026, 8, 5), now = createdAt)

        assertThat(pref.dayStartTime).isEqualTo(LocalTime.of(4, 0))
        assertThat(pref.pendingDayStartTime).isEqualTo(LocalTime.of(3, 0))
        assertThat(pref.pendingEffectiveFrom).isEqualTo(LocalDate.of(2026, 8, 6))
    }
}
