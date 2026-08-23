package com.moeum.conversation.application.command

import com.moeum.conversation.application.publicapi.events.ConversationDayClosedV1
import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.time.LocalDate

class CloseSingleConversationDayServiceTest {

    private class FakeConversationDayRepository(private val closeSucceeds: Boolean) : ConversationDayRepository {
        var closeIfOpenCalls = 0
        override fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay? = null
        override fun save(conversationDay: ConversationDay): ConversationDay = conversationDay
        override fun findOpenDueForClose(now: Instant, limit: Int): List<ConversationDay> = emptyList()
        override fun closeIfOpen(id: ConversationDayId, closedAt: Instant): Boolean {
            closeIfOpenCalls++
            return closeSucceeds
        }
    }

    private class RecordingEventPublisher : ApplicationEventPublisher {
        val published = mutableListOf<Any>()
        override fun publishEvent(event: Any) {
            published.add(event)
        }
    }

    private fun newDay(): ConversationDay =
        ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = UserId.generate(),
            localDate = LocalDate.of(2026, 8, 2),
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-02T01:00:00Z"),
        )

    @Test
    fun `여전히 OPEN이면 CLOSED로 전환하고 ConversationDayClosed를 발행한다`() {
        val repository = FakeConversationDayRepository(closeSucceeds = true)
        val eventPublisher = RecordingEventPublisher()
        val service = CloseSingleConversationDayService(repository, eventPublisher)
        val day = newDay()
        val now = Instant.parse("2026-08-02T15:00:00Z")

        val result = service.closeIfOpen(day, now)

        assertThat(result).isTrue()
        assertThat(repository.closeIfOpenCalls).isEqualTo(1)
        val event = eventPublisher.published.single() as ConversationDayClosedV1
        assertThat(event.conversationDayId).isEqualTo(day.id.value)
        assertThat(event.userId).isEqualTo(day.userId.value)
        assertThat(event.localDate).isEqualTo(day.localDate)
        assertThat(event.occurredAt).isEqualTo(now)
    }

    @Test
    fun `이미 다른 처리로 마감됐으면 이벤트를 발행하지 않는다`() {
        val repository = FakeConversationDayRepository(closeSucceeds = false)
        val eventPublisher = RecordingEventPublisher()
        val service = CloseSingleConversationDayService(repository, eventPublisher)
        val day = newDay()

        val result = service.closeIfOpen(day, Instant.parse("2026-08-02T15:00:00Z"))

        assertThat(result).isFalse()
        assertThat(eventPublisher.published).isEmpty()
    }
}
