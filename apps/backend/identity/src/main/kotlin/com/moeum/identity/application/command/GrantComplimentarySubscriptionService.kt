package com.moeum.identity.application.command

import com.moeum.identity.domain.SubscriptionRepository
import com.moeum.identity.domain.model.Plan
import com.moeum.identity.domain.model.Subscription
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// 결제 없이 구독을 지급한다. 현재 운영 호출부가 없으며 재설계 R4에서 제거한다(docs/DEVELOPMENT_STAGES.md).
@Service
class GrantComplimentarySubscriptionService(
    private val subscriptionRepository: SubscriptionRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun grant(userId: UserId, plan: Plan, endsAt: Instant? = null): Subscription {
        val now = timeProvider.now()
        return subscriptionRepository.save(Subscription.complimentary(userId, plan, startsAt = now, endsAt = endsAt, now = now))
    }
}
