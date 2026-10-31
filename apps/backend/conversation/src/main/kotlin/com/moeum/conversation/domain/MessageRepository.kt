package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.util.UUID

interface MessageRepository {
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(userId: UserId, recordDayId: RecordDayId, after: Long?, limit: Int): List<Message>
    // 이미 답한 범위(원본 순번 이하)의 최근 문맥. 원본 순번별 USER → ASSISTANT 순서다.
    fun findContext(userId: UserId, recordDayId: RecordDayId, throughSourceRevision: Long, limit: Int): List<Message>
    // ASSISTANT의 원본 순번은 그 응답이 답한 범위의 끝이다. 답한 적이 없으면 0.
    fun answeredSourceRevision(userId: UserId, recordDayId: RecordDayId): Long
    // (answered, observed] 범위의 USER를 원본 순번 오름차순으로 최대 limit개 읽는다. 한 호출의 대상 수를 넘는 입력은 읽지 않는다.
    fun findUnanswered(userId: UserId, recordDayId: RecordDayId, answered: Long, observed: Long, limit: Int): List<Message>
    fun findJournalInput(userId: UserId, recordDayId: RecordDayId, sourceRevision: Long, closedAt: Instant): List<Message>
    fun append(message: Message): Message
}
