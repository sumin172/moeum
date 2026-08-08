package com.moeum.conversation.application.query

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class GetTodayConversationServiceTest {

    private class FakeConversationDayRepository : ConversationDayRepository {
        val days = mutableMapOf<Pair<UserId, LocalDate>, ConversationDay>()
        override fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay? =
            days[userId to localDate]
        override fun save(conversationDay: ConversationDay): ConversationDay {
            days[conversationDay.userId to conversationDay.localDate] = conversationDay
            return conversationDay
        }
    }

    private class FakeMessageRepository : MessageRepository {
        val messages = mutableListOf<Message>()
        override fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message? =
            messages.find { it.userId == userId && it.clientMessageId == clientMessageId }
        override fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message> {
            val ordered = messages.filter { it.conversationDayId == conversationDayId }
                .sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
            return if (after != null) {
                ordered.filter { it.id.value > after.value }.take(limit)
            } else {
                ordered.takeLast(limit)
            }
        }
        override fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message> =
            messages.filter { it.conversationDayId == conversationDayId }
                .sortedWith(compareBy({ it.occurredAt }, { it.id.value }))
        override fun save(message: Message): Message {
            messages.add(message)
            return message
        }
        override fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean {
            val index = messages.indexOfFirst { it.id == id }
            if (index < 0 || messages[index].responseStatus != expected) return false
            messages[index] = messages[index].withResponseStatus(updated)
            return true
        }
    }

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    private val userId = UserId.generate()

    @Test
    fun `오늘 대화가 없으면 빈 목록을 반환한다`() {
        val service = GetTodayConversationService(
            FakeConversationDayRepository(),
            FakeMessageRepository(),
            FixedTimeProvider(Instant.parse("2026-08-02T01:00:00Z")),
        )

        val result = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 50)

        assertThat(result.conversationDayId).isNull()
        assertThat(result.messages).isEmpty()
        assertThat(result.localDate).isEqualTo(LocalDate.of(2026, 8, 2))
    }

    @Test
    fun `오늘 저장된 메시지를 시간순으로 반환한다`() {
        val dayRepository = FakeConversationDayRepository()
        val messageRepository = FakeMessageRepository()
        val timeProvider = FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z"))
        val localDate = LocalDate.of(2026, 8, 2)

        val day = ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = userId,
            localDate = localDate,
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-02T00:00:00Z"),
        )
        dayRepository.save(day)

        val later = Message.userMessage(
            id = MessageId.generate(),
            conversationDayId = day.id,
            userId = userId,
            content = "나중 메시지",
            occurredAt = Instant.parse("2026-08-02T09:00:00Z"),
            timezone = "Asia/Seoul",
            localDate = localDate,
            clientMessageId = UUID.randomUUID(),
        )
        val earlier = Message.userMessage(
            id = MessageId.generate(),
            conversationDayId = day.id,
            userId = userId,
            content = "먼저 메시지",
            occurredAt = Instant.parse("2026-08-02T01:00:00Z"),
            timezone = "Asia/Seoul",
            localDate = localDate,
            clientMessageId = UUID.randomUUID(),
        )
        messageRepository.save(later)
        messageRepository.save(earlier)

        val service = GetTodayConversationService(dayRepository, messageRepository, timeProvider)
        val result = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 50)

        assertThat(result.conversationDayId).isEqualTo(day.id)
        assertThat(result.messages).extracting("content").containsExactly("먼저 메시지", "나중 메시지")
    }

    @Test
    fun `previousDay가 true이면 전날 대화를 반환한다`() {
        val dayRepository = FakeConversationDayRepository()
        val messageRepository = FakeMessageRepository()
        val timeProvider = FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z"))
        val yesterday = LocalDate.of(2026, 8, 1)

        val day = ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = userId,
            localDate = yesterday,
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-01T00:00:00Z"),
        )
        dayRepository.save(day)
        messageRepository.save(
            Message.userMessage(
                id = MessageId.generate(),
                conversationDayId = day.id,
                userId = userId,
                content = "어제 메시지",
                occurredAt = Instant.parse("2026-08-01T09:00:00Z"),
                timezone = "Asia/Seoul",
                localDate = yesterday,
                clientMessageId = UUID.randomUUID(),
            ),
        )

        val service = GetTodayConversationService(dayRepository, messageRepository, timeProvider)
        val result = service.getToday(userId, "Asia/Seoul", previousDay = true, after = null, limit = 50)

        assertThat(result.localDate).isEqualTo(yesterday)
        assertThat(result.conversationDayId).isEqualTo(day.id)
        assertThat(result.messages).extracting("content").containsExactly("어제 메시지")
    }

    @Test
    fun `커서 없이 조회하면 최근 limit개를 반환하고, after 커서로 그 이후 새 메시지만 받아온다`() {
        val dayRepository = FakeConversationDayRepository()
        val messageRepository = FakeMessageRepository()
        val timeProvider = FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z"))
        val localDate = LocalDate.of(2026, 8, 2)

        val day = ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = userId,
            localDate = localDate,
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-02T00:00:00Z"),
        )
        dayRepository.save(day)

        (1..4).forEach { i ->
            messageRepository.save(
                Message.userMessage(
                    id = MessageId.generate(),
                    conversationDayId = day.id,
                    userId = userId,
                    content = "메시지$i",
                    occurredAt = Instant.parse("2026-08-02T0$i:00:00Z"),
                    timezone = "Asia/Seoul",
                    localDate = localDate,
                    clientMessageId = UUID.randomUUID(),
                ),
            )
        }

        val service = GetTodayConversationService(dayRepository, messageRepository, timeProvider)

        // 커서 없이 조회하면 최근 2개(메시지3, 메시지4)만 온다
        val firstPage = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 2)
        assertThat(firstPage.messages).extracting("content").containsExactly("메시지3", "메시지4")

        // 그 뒤에 새 메시지가 도착
        messageRepository.save(
            Message.userMessage(
                id = MessageId.generate(),
                conversationDayId = day.id,
                userId = userId,
                content = "메시지5",
                occurredAt = Instant.parse("2026-08-02T05:00:00Z"),
                timezone = "Asia/Seoul",
                localDate = localDate,
                clientMessageId = UUID.randomUUID(),
            ),
        )

        // 클라이언트가 마지막으로 받은 메시지(메시지4) 이후로 델타 조회
        val cursor = firstPage.messages.last().id
        val delta = service.getToday(userId, "Asia/Seoul", previousDay = false, after = cursor, limit = 2)
        assertThat(delta.messages).extracting("content").containsExactly("메시지5")
    }
}
