package com.moeum.identity.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.util.UUID

data class SubscriptionId(val value: UUID)

// 구독이 없으면 FREE로 본다(FREE는 구독 레코드로 남기지 않는다).
enum class Plan { FREE, PREMIUM }

// 사용자에게 유료 요금제가 적용되는 기간. 결제와 무관하게 "누가 언제까지 어떤 요금제인가"만 표현한다.
data class Subscription(
    val id: SubscriptionId,
    val userId: UserId,
    val plan: Plan,
    val startsAt: Instant,
    // null이면 무기한
    val endsAt: Instant? = null,
    val revokedAt: Instant? = null,
    val createdAt: Instant,
)
