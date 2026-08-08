package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import java.util.UUID

interface MessageRepository {
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message>
    fun save(message: Message): Message
}
