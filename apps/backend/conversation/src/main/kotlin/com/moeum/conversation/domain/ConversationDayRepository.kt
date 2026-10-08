package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.kernel.UserId
import java.time.LocalDate

interface ConversationDayRepository {
    fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay?
    fun save(conversationDay: ConversationDay): ConversationDay
}
