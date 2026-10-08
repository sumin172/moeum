package com.moeum.conversation.application.command

import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryDayPreferenceRepository
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ChangeDayStartTimeServiceTest {

    private val userId = UserId.generate()
    private val repository = InMemoryDayPreferenceRepository()

    @Test
    fun `처음 변경하면 기본값 위에 내일부터 적용되는 예약 변경으로 저장한다`() {
        // KST 8/3 01:00 — 기본 경계(02:00) 전이라 사용자의 "오늘"은 8/2, 변경은 8/3부터
        val service = ChangeDayStartTimeService(repository, FixedTimeProvider(Instant.parse("2026-08-02T16:00:00Z")))

        service.change(userId, LocalTime.of(4, 0, 30), "Asia/Seoul")

        val saved = repository.preferences.getValue(userId)
        assertThat(saved.dayStartTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(saved.pendingDayStartTime).isEqualTo(LocalTime.of(4, 0))
        assertThat(saved.pendingEffectiveFrom).isEqualTo(LocalDate.of(2026, 8, 3))
    }

    @Test
    fun `timezone이 유효하지 않으면 거부한다`() {
        val service = ChangeDayStartTimeService(repository, FixedTimeProvider(Instant.parse("2026-08-02T16:00:00Z")))

        assertThatThrownBy { service.change(userId, LocalTime.of(4, 0), "Not/AZone") }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }
}
