package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

interface ConversationDayRepository {
    fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay?
    fun save(conversationDay: ConversationDay): ConversationDay

    // now 기준 마감 시각이 지난 OPEN day를 최대 limit개 조회한다 (배치 스캔용).
    fun findOpenDueForClose(now: Instant, limit: Int): List<ConversationDay>

    // id가 여전히 OPEN일 때만 CLOSED로 원자적 전환한다. 이미 다른 호출이 먼저 닫았으면 false.
    fun closeIfOpen(id: ConversationDayId, closedAt: Instant): Boolean
}
