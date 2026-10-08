package com.moeum.conversation.interfaces.dto

import java.time.LocalTime

data class ChangeDayStartTimeRequest(
    val dayStartTime: LocalTime,
    // "내일"을 계산할 기준 timezone (사용자가 지금 있는 곳)
    val timezone: String,
)
