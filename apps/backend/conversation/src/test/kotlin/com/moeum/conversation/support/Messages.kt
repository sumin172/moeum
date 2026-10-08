package com.moeum.conversation.support

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

fun userMessage(
    userId: UserId,
    content: String,
    occurredAt: Instant,
    dayDate: LocalDate,
    timezone: String = "Asia/Seoul",
    createdAt: Instant = occurredAt,
): Message =
    Message.userMessage(
        id = MessageId.generate(),
        userId = userId,
        content = content,
        occurredAt = occurredAt,
        timezone = timezone,
        localDate = occurredAt.atZone(ZoneId.of(timezone)).toLocalDate(),
        dayDate = dayDate,
        clientMessageId = UUID.randomUUID(),
        now = createdAt,
    )
