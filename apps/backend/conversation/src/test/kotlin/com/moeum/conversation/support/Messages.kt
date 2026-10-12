package com.moeum.conversation.support

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.util.*

fun userMessage(
    userId: UserId,
    content: String,
    occurredAt: Instant,
    recordDayId: RecordDayId,
    timezone: String = "Asia/Seoul",
    createdAt: Instant = occurredAt,
    sourceSequence: Long = 1,
): Message =
    Message.userMessage(
        id = MessageId.generate(),
        userId = userId,
        content = content,
        occurredAt = occurredAt,
        timezone = timezone,
        recordDayId = recordDayId,
        sourceSequence = sourceSequence,
        clientMessageId = UUID.randomUUID(),
        now = createdAt,
    )
