package com.moeum.conversation.application.query

import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.findOrDefault
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.parseTimezone
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import java.time.LocalDate

data class TodayConversation(
    val dayDate: LocalDate,
    val messages: List<Message>,
    // 유저 메시지별 AI 응답 작업 상태
    val responseJobs: Map<MessageId, ResponseJob>,
)

@Service
class GetTodayConversationService(
    private val dayPreferenceRepository: DayPreferenceRepository,
    private val messageRepository: MessageRepository,
    private val responseJobRepository: ResponseJobRepository,
    private val timeProvider: TimeProvider,
) {
    fun getToday(userId: UserId, timezone: String, previousDay: Boolean, after: MessageId?, limit: Int): TodayConversation {
        val zoneId = parseTimezone(timezone)
        val now = timeProvider.now()
        val today = dayPreferenceRepository.findOrDefault(userId, now).dayDateOf(now, zoneId)
        val dayDate = if (previousDay) today.minusDays(1) else today

        val messages = messageRepository.findPage(userId, dayDate, after, limit)
        val userMessageIds = messages.filter { it.role == MessageRole.USER }.map { it.id }
        val responseJobs = responseJobRepository.findAllByUserIdAndUserMessageIdIn(userId, userMessageIds).associateBy { it.userMessageId }
        return TodayConversation(dayDate = dayDate, messages = messages, responseJobs = responseJobs)
    }
}
