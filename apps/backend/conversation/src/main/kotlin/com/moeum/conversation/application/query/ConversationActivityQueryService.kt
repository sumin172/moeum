package com.moeum.conversation.application.query

import com.moeum.conversation.application.publicapi.ActiveDay
import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.findOrDefault
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class ConversationActivityQueryService(
    private val messageRepository: MessageRepository,
    private val dayPreferenceRepository: DayPreferenceRepository,
    private val timeProvider: TimeProvider,
) : ConversationActivityQuery {

    override fun findActiveDays(from: Instant, to: Instant): List<ActiveDay> {
        val now = timeProvider.now()
        val messagesByDay = messageRepository.findCreatedBetween(from, to).groupBy { it.userId to it.dayDate }
        val preferences = messagesByDay.keys.map { it.first }.distinct()
            .associateWith { dayPreferenceRepository.findOrDefault(it, now) }

        return messagesByDay.map { (key, messages) ->
            val (userId, dayDate) = key
            val latestZone = ZoneId.of(messages.maxBy { it.occurredAt }.timezone)
            ActiveDay(userId = userId, dayDate = dayDate, dayEnd = preferences.getValue(userId).endOf(dayDate, latestZone))
        }
    }

    override fun findMessages(userId: UserId, dayDate: LocalDate): List<MessageSnapshot> =
        messageRepository.findAllByUserIdAndDayDate(userId, dayDate).map {
            MessageSnapshot(role = it.role.name, content = it.content, occurredAt = it.occurredAt)
        }
}
