package com.moeum.conversation.domain

import java.time.DateTimeException
import java.time.ZoneId

fun parseTimezone(timezone: String): ZoneId =
    try {
        ZoneId.of(timezone)
    } catch (_: DateTimeException) {
        throw InvalidConversationRequestException("유효하지 않은 timezone입니다: $timezone")
    }
