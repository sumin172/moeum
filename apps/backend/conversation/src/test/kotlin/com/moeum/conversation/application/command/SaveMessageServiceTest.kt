package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.ConversationDayStatus
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SaveMessageServiceTest {

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
        override fun save(message: Message): Message {
            messages.add(message)
            return message
        }
    }

    private class FixedTimeProvider(private val fixedNow: Instant) : TimeProvider {
        override fun now(): Instant = fixedNow
        override fun today(zoneId: ZoneId): LocalDate = fixedNow.atZone(zoneId).toLocalDate()
    }

    private val userId = UserId.generate()

    private fun newService(
        conversationDayRepository: FakeConversationDayRepository = FakeConversationDayRepository(),
        messageRepository: FakeMessageRepository = FakeMessageRepository(),
        timeProvider: TimeProvider = FixedTimeProvider(Instant.parse("2026-08-02T00:00:00Z")),
    ) = SaveMessageService(conversationDayRepository, messageRepository, timeProvider)

    @Test
    fun `첫 메시지를 저장하면 새 ConversationDay가 OPEN 상태로 생성된다`() {
        val dayRepository = FakeConversationDayRepository()
        val service = newService(conversationDayRepository = dayRepository)
        val command = SaveMessageCommand(
            clientMessageId = UUID.randomUUID(),
            content = "오늘 정말 피곤했다",
            occurredAt = Instant.parse("2026-08-02T05:00:00Z"),
            timezone = "Asia/Seoul",
        )

        val message = service.save(userId, command)

        val localDate = LocalDate.of(2026, 8, 2)
        val day = dayRepository.days.getValue(userId to localDate)
        assertThat(day.status).isEqualTo(ConversationDayStatus.OPEN)
        assertThat(day.sourceRevision).isEqualTo(1)
        assertThat(message.conversationDayId).isEqualTo(day.id)
    }

    @Test
    fun `같은 날짜에 메시지를 또 보내면 기존 ConversationDay를 재사용하고 sourceRevision이 증가한다`() {
        val dayRepository = FakeConversationDayRepository()
        val messageRepository = FakeMessageRepository()
        val service = newService(conversationDayRepository = dayRepository, messageRepository = messageRepository)
        val timezone = "Asia/Seoul"

        val first = service.save(
            userId,
            SaveMessageCommand(UUID.randomUUID(), "첫 메시지", Instant.parse("2026-08-02T01:00:00Z"), timezone),
        )
        val second = service.save(
            userId,
            SaveMessageCommand(UUID.randomUUID(), "두번째 메시지", Instant.parse("2026-08-02T02:00:00Z"), timezone),
        )

        assertThat(second.conversationDayId).isEqualTo(first.conversationDayId)
        val day = dayRepository.days.getValue(userId to LocalDate.of(2026, 8, 2))
        assertThat(day.sourceRevision).isEqualTo(2)
        assertThat(messageRepository.messages).hasSize(2)
    }

    @Test
    fun `동일한 clientMessageId로 재요청하면 중복 저장하지 않고 기존 메시지를 반환한다`() {
        val messageRepository = FakeMessageRepository()
        val service = newService(messageRepository = messageRepository)
        val clientMessageId = UUID.randomUUID()
        val command = SaveMessageCommand(
            clientMessageId = clientMessageId,
            content = "재전송 테스트",
            occurredAt = Instant.parse("2026-08-02T03:00:00Z"),
            timezone = "Asia/Seoul",
        )

        val first = service.save(userId, command)
        val retried = service.save(userId, command)

        assertThat(retried.id).isEqualTo(first.id)
        assertThat(messageRepository.messages).hasSize(1)
    }

    @Test
    fun `content가 비어있으면 저장을 거부한다`() {
        val service = newService()
        val command = SaveMessageCommand(UUID.randomUUID(), "   ", Instant.now(), "Asia/Seoul")

        assertThatThrownBy { service.save(userId, command) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }

    @Test
    fun `timezone이 유효하지 않으면 저장을 거부한다`() {
        val service = newService()
        val command = SaveMessageCommand(UUID.randomUUID(), "내용", Instant.now(), "Not/AZone")

        assertThatThrownBy { service.save(userId, command) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }
}
