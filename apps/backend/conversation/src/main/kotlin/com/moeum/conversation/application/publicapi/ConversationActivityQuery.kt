package com.moeum.conversation.application.publicapi

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ConversationActivityQuery {
    // [from, to) 구간에 새 메시지가 저장된 (사용자, 하루). 저장 시각 기준이라, 오프라인으로 늦게 도착해
    // 과거 하루에 들어간 메시지도 도착한 시점에 잡힌다.
    fun findActiveDays(from: Instant, to: Instant): List<ActiveDay>

    // 특정 사용자의 하루 원본 메시지, 발화 순서대로.
    fun findMessages(userId: UserId, dayDate: LocalDate): List<MessageSnapshot>
}

data class ActiveDay(
    val userId: UserId,
    val dayDate: LocalDate,
    // 이 하루가 끝나는 시각. 사용자 하루 경계와, 그 하루에서 가장 최근에 관측된 timezone으로 계산한다.
    val dayEnd: Instant,
    // 이번 구간에 저장된 유저 메시지 중 가장 큰 id(UUIDv7, 저장 순서). 확정된 일기가 이 하루의 새 발화를 놓쳤는지
    // (OUTDATED) 판단에 쓴다. 이번 구간에 AI 응답만 저장됐으면 null.
    val lastUserMessageId: UUID?,
)

data class MessageSnapshot(
    // UUIDv7 — 저장 순서대로 커진다
    val id: UUID,
    val role: String,
    val content: String,
    val occurredAt: Instant,
)
