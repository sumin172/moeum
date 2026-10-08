package com.moeum.conversation.application.publicapi

import com.moeum.kernel.UserId
import java.time.Instant

interface ConversationActivityQuery {
    // [from, to) 구간의 원시 활동. "diary day"라는 해석은 이 인터페이스에 등장하지 않는다 —
    // 그걸 diary day로 묶는 건 호출부(Journal)의 책임이다.
    fun findActivities(from: Instant, to: Instant): List<UserActivity>

    // [from, to) 구간, 특정 사용자의 원본 메시지.
    fun findMessages(userId: UserId, from: Instant, to: Instant): List<MessageSnapshot>
}

data class UserActivity(
    val userId: UserId,
    val occurredAt: Instant,
    val timezone: String,
)

data class MessageSnapshot(
    val role: String,
    val content: String,
    val occurredAt: Instant,
)
