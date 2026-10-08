package com.moeum.conversation.application.publicapi

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate

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
)

data class MessageSnapshot(
    val role: String,
    val content: String,
    val occurredAt: Instant,
)
