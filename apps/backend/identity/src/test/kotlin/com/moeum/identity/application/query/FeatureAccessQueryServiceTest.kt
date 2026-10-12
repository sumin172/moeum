package com.moeum.identity.application.query

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.domain.SubscriptionRepository
import com.moeum.identity.domain.model.Plan
import com.moeum.identity.domain.model.Subscription
import com.moeum.identity.infrastructure.config.FeaturePlanProperties
import com.moeum.identity.support.MutableTimeProvider
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class FeatureAccessQueryServiceTest {

    private class InMemorySubscriptionRepository : SubscriptionRepository {
        val subscriptions = mutableListOf<Subscription>()
        override fun findActiveByUserId(userId: UserId, now: Instant): List<Subscription> = subscriptions.filter { it.userId == userId && it.revokedAt==null && !now.isBefore(it.startsAt) && (it.endsAt==null || now.isBefore(it.endsAt)) }
    }

    private val timeProvider = MutableTimeProvider(Instant.parse("2026-10-08T00:00:00Z"))
    private val repository = InMemorySubscriptionRepository()
    private val query = FeatureAccessQueryService(repository, FeaturePlanProperties(), timeProvider, com.moeum.identity.infrastructure.config.ConversationPlanProperties())
    private val userId = UserId.generate()

    private fun premium(endsAt: Instant? = null, startsAt: Instant = timeProvider.now, revokedAt: Instant? = null) {
        repository.subscriptions += Subscription(
            com.moeum.identity.domain.model.SubscriptionId(java.util.UUID.randomUUID()),userId,Plan.PREMIUM,
            startsAt,endsAt,revokedAt,timeProvider.now,
        )
    }

    @Test
    fun `구독이 없는 사용자는 FREE라 일기를 고칠 수 없다`() {
        assertThat(query.isEnabled(userId, Feature.JOURNAL_EDIT)).isFalse()
    }

    @Test
    fun `무료로 지급한 프리미엄 구독이 유효한 동안 일기를 고칠 수 있다`() {
        premium(endsAt = timeProvider.now.plus(Duration.ofDays(30)))

        assertThat(query.isEnabled(userId, Feature.JOURNAL_EDIT)).isTrue()

        timeProvider.advance(Duration.ofDays(31))
        assertThat(query.isEnabled(userId, Feature.JOURNAL_EDIT)).isFalse()
    }

    @Test
    fun `기한 없는 지급은 계속 유효하고, 다른 사용자에게는 영향이 없다`() {
        premium()
        timeProvider.advance(Duration.ofDays(3650))

        assertThat(query.isEnabled(userId, Feature.JOURNAL_EDIT)).isTrue()
        assertThat(query.isEnabled(UserId.generate(), Feature.JOURNAL_EDIT)).isFalse()
    }

    @Test
    fun `미래 구독과 취소된 구독은 수정 권한이나 높은 대화 한도를 열지 않는다`() {
        premium(startsAt=timeProvider.now.plusSeconds(60))
        premium(revokedAt=timeProvider.now)
        assertThat(query.isEnabled(userId,Feature.JOURNAL_EDIT)).isFalse()
        assertThat(query.dailyLimit(userId)).isEqualTo(20)
    }
}
