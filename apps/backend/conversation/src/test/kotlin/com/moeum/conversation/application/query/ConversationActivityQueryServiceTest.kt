package com.moeum.conversation.application.query

import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryDayPreferenceRepository
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ConversationActivityQueryServiceTest {

    private val userId = UserId.generate()
    private val messageRepository = InMemoryMessageRepository()
    private val service = ConversationActivityQueryService(
        messageRepository,
        InMemoryDayPreferenceRepository(),
        FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z")),
    )

    @Test
    fun `구간 안에 저장된 메시지를 사용자별 하루로 묶고, 하루가 끝나는 시각을 함께 준다`() {
        val day = LocalDate.of(2026, 8, 2)
        messageRepository.save(userMessage(userId, "a", Instant.parse("2026-08-02T01:00:00Z"), day))
        messageRepository.save(userMessage(userId, "b", Instant.parse("2026-08-02T02:00:00Z"), day))

        val activeDays = service.findActiveDays(Instant.parse("2026-08-02T00:00:00Z"), Instant.parse("2026-08-02T03:00:00Z"))

        assertThat(activeDays).hasSize(1)
        assertThat(activeDays.single().dayDate).isEqualTo(day)
        // 기본 경계 02:00 KST → 8/2 하루는 8/3 02:00 KST(= 8/2 17:00Z)에 끝난다
        assertThat(activeDays.single().dayEnd).isEqualTo(Instant.parse("2026-08-02T17:00:00Z"))
    }

    @Test
    fun `저장 시각 기준이라 오프라인으로 늦게 도착한 과거 하루의 메시지도 잡힌다`() {
        val pastDay = LocalDate.of(2026, 7, 30)
        messageRepository.save(
            userMessage(
                userId, "비행기에서 쓴 메시지", Instant.parse("2026-07-30T05:00:00Z"), pastDay,
                createdAt = Instant.parse("2026-08-02T09:30:00Z"),
            ),
        )

        val activeDays = service.findActiveDays(Instant.parse("2026-08-02T09:00:00Z"), Instant.parse("2026-08-02T10:00:00Z"))

        assertThat(activeDays.map { it.dayDate }).containsExactly(pastDay)
    }

    @Test
    fun `하루의 원본 메시지를 발화 순서대로 돌려준다`() {
        val day = LocalDate.of(2026, 8, 2)
        messageRepository.save(userMessage(userId, "둘째", Instant.parse("2026-08-02T05:00:00Z"), day))
        messageRepository.save(userMessage(userId, "첫째", Instant.parse("2026-08-02T03:00:00Z"), day))
        messageRepository.save(userMessage(userId, "다른 날", Instant.parse("2026-08-03T05:00:00Z"), day.plusDays(1)))

        assertThat(service.findMessages(userId, day).map { it.content }).containsExactly("첫째", "둘째")
    }
}
