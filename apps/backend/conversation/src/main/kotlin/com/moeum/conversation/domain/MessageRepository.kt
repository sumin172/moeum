package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface MessageRepository {
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(userId: UserId, dayDate: LocalDate, after: MessageId?, limit: Int): List<Message>
    fun findAllByUserIdAndDayDate(userId: UserId, dayDate: LocalDate): List<Message>
    // [from, to) 구간에 저장(createdAt)된 메시지, 전체 사용자 대상 — Journal이 "새 활동이 생긴 하루"를 찾는 데 쓴다.
    fun findCreatedBetween(from: Instant, to: Instant): List<Message>
    fun save(message: Message): Message
    fun compareAndSetStatus(id: MessageId, expected: MessageResponseStatus, updated: MessageResponseStatus): Boolean
}
