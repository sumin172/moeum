package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 사용자 소유 데이터라 조회는 항상 user_id를 포함한다 — 탈퇴 연쇄 삭제·export, 향후 user_id 파티셔닝(파티션 하나만 조회) 대비.
interface MessageRepository {
    fun findByUserIdAndId(userId: UserId, id: MessageId): Message?
    fun findByUserIdAndClientMessageId(userId: UserId, clientMessageId: UUID): Message?
    fun findPage(userId: UserId, dayDate: LocalDate, after: MessageId?, limit: Int): List<Message>
    fun findAllByUserIdAndDayDate(userId: UserId, dayDate: LocalDate): List<Message>
    // [from, to) 구간에 저장(createdAt)된 메시지, 전체 사용자 대상 — Journal이 "새 활동이 생긴 하루"를 찾는 데 쓴다.
    fun findCreatedBetween(from: Instant, to: Instant): List<Message>
    // 메시지는 저장 후 바뀌지 않는다(사용자 원본은 불변, AI 응답도 생성 기록) — 수정용 save 없이 추가만 한다.
    // 삭제가 생기면 deleted_at만 바꾸는 전용 메서드로 한다.
    fun append(message: Message): Message
}
