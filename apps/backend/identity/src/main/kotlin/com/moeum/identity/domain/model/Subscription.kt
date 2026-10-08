package com.moeum.identity.domain.model

import com.moeum.kernel.UserId
import com.moeum.kernel.UuidV7
import java.time.Instant
import java.util.UUID

data class SubscriptionId(val value: UUID) {
    companion object {
        fun generate(): SubscriptionId = SubscriptionId(UuidV7.generate())
    }
}

// 구독이 없으면 FREE로 본다(FREE는 구독 레코드로 남기지 않는다).
enum class Plan { FREE, PREMIUM }

enum class SubscriptionSource {
    // 결제 없이 지급(초기 사용자 혜택, 프로모션 등)
    COMPLIMENTARY,

    // 결제로 생긴 구독 — Stage 6
    PAID,
}

// 사용자에게 유료 요금제가 적용되는 기간. 결제와 무관하게 "누가 언제까지 어떤 요금제인가"만 표현한다.
data class Subscription(
    val id: SubscriptionId,
    val userId: UserId,
    val plan: Plan,
    val source: SubscriptionSource,
    val startsAt: Instant,
    // null이면 무기한
    val endsAt: Instant? = null,
    val revokedAt: Instant? = null,
    val createdAt: Instant,
) {
    fun isActive(now: Instant): Boolean =
        revokedAt == null && !now.isBefore(startsAt) && (endsAt == null || now.isBefore(endsAt))

    companion object {
        fun complimentary(userId: UserId, plan: Plan, startsAt: Instant, endsAt: Instant?, now: Instant): Subscription {
            require(plan != Plan.FREE) { "FREE는 구독으로 지급하지 않습니다" }
            return Subscription(
                id = SubscriptionId.generate(),
                userId = userId,
                plan = plan,
                source = SubscriptionSource.COMPLIMENTARY,
                startsAt = startsAt,
                endsAt = endsAt,
                createdAt = now,
            )
        }
    }
}
