package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.kernel.UserId
import java.util.UUID

interface MessageRepository {
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message>
    fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message>
    fun save(message: Message): Message
    fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean
}
