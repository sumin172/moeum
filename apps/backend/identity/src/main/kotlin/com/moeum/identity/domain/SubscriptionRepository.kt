package com.moeum.identity.domain

import com.moeum.identity.domain.model.Subscription
import com.moeum.kernel.UserId

interface SubscriptionRepository {
    fun findActiveByUserId(userId: UserId, now: java.time.Instant): List<Subscription>
    fun save(subscription: Subscription): Subscription
}
