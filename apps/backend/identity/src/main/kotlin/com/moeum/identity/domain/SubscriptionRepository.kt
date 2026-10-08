package com.moeum.identity.domain

import com.moeum.identity.domain.model.Subscription
import com.moeum.kernel.UserId

interface SubscriptionRepository {
    fun findAllByUserId(userId: UserId): List<Subscription>
    fun save(subscription: Subscription): Subscription
}
