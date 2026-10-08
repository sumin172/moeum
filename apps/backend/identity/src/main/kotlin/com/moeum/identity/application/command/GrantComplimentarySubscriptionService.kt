package com.moeum.identity.application.command

import com.moeum.identity.domain.SubscriptionRepository
import com.moeum.identity.domain.model.Plan
import com.moeum.identity.domain.model.Subscription
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// 결제 없이 구독을 지급한다(초기 사용자 혜택 등). 관리자 도구가 생기기 전(Stage 6)까지는 운영자가 이 경로나
// 문서화된 SQL(docs/ARCHITECTURE.md "기능 권한과 구독")로 지급한다.
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
