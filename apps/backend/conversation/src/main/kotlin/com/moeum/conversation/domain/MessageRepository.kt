package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.kernel.UserId
import java.time.Instant
import java.util.UUID

interface MessageRepository {
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(conversationDayId: ConversationDayId, after: MessageId?, limit: Int): List<Message>
    fun findAllByConversationDayId(conversationDayId: ConversationDayId): List<Message>
    // [from, to) 구간, 전체 사용자 대상 — GenerationPlanner가 "최근 활동"을 찾는 데 쓴다.
    fun findByOccurredAtRange(from: Instant, to: Instant): List<Message>
    // [from, to) 구간, 특정 사용자 — GenerationExecutor가 diary window 원본을 조회하는 데 쓴다.
    fun findByUserIdAndOccurredAtRange(userId: UserId, from: Instant, to: Instant): List<Message>
    fun save(message: Message): Message
    fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean
}
