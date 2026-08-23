package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CloseConversationDaysServiceTest {

    private class FakeConversationDayRepository : ConversationDayRepository {
        var dueDays: List<ConversationDay> = emptyList()
        val closedIds = mutableListOf<ConversationDayId>()
        var closeShouldFailFor: ConversationDayId? = null

        override fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay? = null
        override fun save(conversationDay: ConversationDay): ConversationDay = conversationDay
        override fun findOpenDueForClose(now: Instant, limit: Int): List<ConversationDay> = dueDays.take(limit)
        override fun closeIfOpen(id: ConversationDayId, closedAt: Instant): Boolean {
            if (id == closeShouldFailFor) return false
            closedIds.add(id)
            return true
        }
    }

    private class NoOpEventPublisher : ApplicationEventPublisher {
        override fun publishEvent(event: Any) {}
    }

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    private fun newDay(localDate: LocalDate = LocalDate.of(2026, 8, 2)): ConversationDay =
        ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = UserId.generate(),
            localDate = localDate,
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-02T01:00:00Z"),
        )

    private fun newService(repository: FakeConversationDayRepository) = CloseConversationDaysService(
        conversationDayRepository = repository,
        closeSingleConversationDayService = CloseSingleConversationDayService(repository, NoOpEventPublisher()),
        timeProvider = FixedTimeProvider(Instant.parse("2026-08-02T15:00:00Z")),
    )

    @Test
    fun `마감 대상 day를 모두 닫고 닫은 건수를 반환한다`() {
        val repository = FakeConversationDayRepository()
        repository.dueDays = listOf(newDay(), newDay(LocalDate.of(2026, 8, 3)))
        val service = newService(repository)

        val closedCount = service.closeDueDays(batchSize = 200)

        assertThat(closedCount).isEqualTo(2)
        assertThat(repository.closedIds).hasSize(2)
    }

    @Test
    fun `경합으로 이미 닫힌 day는 카운트에서 제외된다`() {
        val repository = FakeConversationDayRepository()
        val alreadyClosed = newDay()
        val stillOpen = newDay(LocalDate.of(2026, 8, 3))
        repository.dueDays = listOf(alreadyClosed, stillOpen)
        repository.closeShouldFailFor = alreadyClosed.id
        val service = newService(repository)

        val closedCount = service.closeDueDays(batchSize = 200)

        assertThat(closedCount).isEqualTo(1)
        assertThat(repository.closedIds).containsExactly(stillOpen.id)
    }

    @Test
    fun `대상이 없으면 0을 반환한다`() {
        val repository = FakeConversationDayRepository()
        val service = newService(repository)

        assertThat(service.closeDueDays(batchSize = 200)).isEqualTo(0)
    }
}
