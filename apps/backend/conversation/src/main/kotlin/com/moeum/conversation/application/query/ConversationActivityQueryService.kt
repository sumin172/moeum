package com.moeum.conversation.application.query

import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.conversation.application.publicapi.UserActivity
import com.moeum.conversation.domain.MessageRepository
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ConversationActivityQueryService(
    private val messageRepository: MessageRepository,
) : ConversationActivityQuery {

    override fun findActivities(from: Instant, to: Instant): List<UserActivity> =
        messageRepository.findByOccurredAtRange(from, to).map {
            UserActivity(userId = it.userId, occurredAt = it.occurredAt, timezone = it.timezone)
        }

    override fun findMessages(userId: UserId, from: Instant, to: Instant): List<MessageSnapshot> =
        messageRepository.findByUserIdAndOccurredAtRange(userId, from, to).map {
            MessageSnapshot(role = it.role.name, content = it.content, occurredAt = it.occurredAt)
        }
}
