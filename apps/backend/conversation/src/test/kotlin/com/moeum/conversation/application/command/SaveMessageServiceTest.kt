package com.moeum.conversation.application.command

import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.model.DayPreference
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryDayPreferenceRepository
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class SaveMessageServiceTest {

    private val userId = UserId.generate()
    private val now = Instant.parse("2026-08-02T10:00:00Z")
    private val messageRepository = InMemoryMessageRepository()
    private val dayPreferenceRepository = InMemoryDayPreferenceRepository()
    private val service = SaveMessageService(dayPreferenceRepository, messageRepository, FixedTimeProvider(now))

    private fun command(occurredAt: String, content: String = "내용", clientMessageId: UUID = UUID.randomUUID()) =
        SaveMessageCommand(clientMessageId, content, Instant.parse(occurredAt), "Asia/Seoul")

    @Test
    fun `메시지는 기본 하루 경계(02시)로 계산한 dayDate와 달력 날짜를 함께 저장한다`() {
        // KST 2026-08-02 01:30 — 달력으로는 8/2지만 하루 경계(02:00) 전이라 8/1 하루에 속한다
        val result = service.save(userId, command("2026-08-01T16:30:00Z"))

        assertThat(result.isNewlyCreated).isTrue()
        assertThat(result.message.localDate).isEqualTo(LocalDate.of(2026, 8, 2))
        assertThat(result.message.dayDate).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(result.message.createdAt).isEqualTo(now)
    }

    @Test
    fun `사용자가 설정한 하루 경계로 dayDate를 계산한다`() {
        dayPreferenceRepository.save(
            DayPreference(userId = userId, dayStartTime = LocalTime.of(0, 0), createdAt = now, updatedAt = now),
        )

        val result = service.save(userId, command("2026-08-01T16:30:00Z"))

        assertThat(result.message.dayDate).isEqualTo(LocalDate.of(2026, 8, 2))
    }

    @Test
    fun `오프라인으로 늦게 도착한 메시지는 발화 시각 기준 하루에 들어간다`() {
        // 저장 시각(now)은 8/2 19:00 KST지만 발화는 7/31 12:00 KST
        val result = service.save(userId, command("2026-07-31T03:00:00Z"))

        assertThat(result.message.dayDate).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test
    fun `동일한 clientMessageId로 재요청하면 중복 저장하지 않고 기존 메시지를 반환한다`() {
        val clientMessageId = UUID.randomUUID()

        val first = service.save(userId, command("2026-08-02T03:00:00Z", clientMessageId = clientMessageId))
        val retried = service.save(userId, command("2026-08-02T03:00:00Z", clientMessageId = clientMessageId))

        assertThat(retried.message.id).isEqualTo(first.message.id)
        assertThat(retried.isNewlyCreated).isFalse()
        assertThat(messageRepository.messages).hasSize(1)
    }

    @Test
    fun `content가 비어있으면 저장을 거부한다`() {
        assertThatThrownBy { service.save(userId, command("2026-08-02T03:00:00Z", content = "   ")) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }

    @Test
    fun `timezone이 유효하지 않으면 저장을 거부한다`() {
        val invalid = SaveMessageCommand(UUID.randomUUID(), "내용", Instant.parse("2026-08-02T03:00:00Z"), "Not/AZone")

        assertThatThrownBy { service.save(userId, invalid) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }
}
