package com.moeum.conversation.interfaces.dto

import com.moeum.conversation.application.query.TodayConversation
import java.time.LocalDate

data class TodayConversationResponse(
    // 사용자 하루 경계 기준의 하루(자정 기준 달력 날짜가 아님)
    val dayDate: LocalDate,
    val messages: List<MessageResponse>,
) {
    companion object {
        fun from(result: TodayConversation): TodayConversationResponse =
            TodayConversationResponse(
                dayDate = result.dayDate,
                messages = result.messages.map { MessageResponse.from(it) },
            )
    }
}
