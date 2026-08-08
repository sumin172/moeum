package com.moeum.conversation.interfaces.dto

import com.moeum.conversation.application.query.TodayConversation
import java.time.LocalDate
import java.util.UUID

data class TodayConversationResponse(
    val localDate: LocalDate,
    val conversationDayId: UUID?,
    val messages: List<MessageResponse>,
) {
    companion object {
        fun from(result: TodayConversation): TodayConversationResponse =
            TodayConversationResponse(
                localDate = result.localDate,
                conversationDayId = result.conversationDayId?.value,
                messages = result.messages.map { MessageResponse.from(it) },
            )
    }
}
