package com.moeum.conversation.application.query

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.parseTimezone
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import java.time.LocalDate

data class TodayConversation(
    val localDate: LocalDate,
    val conversationDayId: ConversationDayId?,
    val messages: List<Message>,
)

@Service
class GetTodayConversationService(
    private val conversationDayRepository: ConversationDayRepository,
    private val messageRepository: MessageRepository,
    private val timeProvider: TimeProvider,
) {
    fun getToday(userId: UserId, timezone: String, previousDay: Boolean, after: MessageId?, limit: Int): TodayConversation {
        val zoneId = parseTimezone(timezone)
        val today = timeProvider.today(zoneId)
        val localDate = if (previousDay) today.minusDays(1) else today

        val conversationDay = conversationDayRepository.findByUserIdAndLocalDate(userId, localDate)
            ?: return TodayConversation(localDate = localDate, conversationDayId = null, messages = emptyList())

        val messages = messageRepository.findPage(conversationDay.id, after, limit)
        return TodayConversation(localDate = localDate, conversationDayId = conversationDay.id, messages = messages)
    }
}
