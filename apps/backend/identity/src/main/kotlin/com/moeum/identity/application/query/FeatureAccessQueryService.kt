package com.moeum.identity.application.query

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.identity.domain.SubscriptionRepository
import com.moeum.identity.domain.model.Plan
import com.moeum.identity.infrastructure.config.FeaturePlanProperties
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service

@Service
class FeatureAccessQueryService(
    private val subscriptionRepository: SubscriptionRepository,
    private val featurePlanProperties: FeaturePlanProperties,
    private val timeProvider: TimeProvider,
    private val conversationPlans: com.moeum.identity.infrastructure.config.ConversationPlanProperties,
) : FeatureAccessQuery, com.moeum.identity.application.publicapi.ConversationAllowanceQuery {

    override fun isEnabled(userId: UserId, feature: Feature): Boolean =
        activePlans(userId).any { feature in featurePlanProperties.featuresOf(it) }

    override fun dailyLimit(userId: UserId): Int =
        if (Plan.PREMIUM in activePlans(userId)) conversationPlans.premiumDailyLimit else conversationPlans.freeDailyLimit

    // 유효한 구독이 하나도 없으면 FREE. 여러 구독이 겹치면 그중 하나라도 기능을 열면 허용한다.
    private fun activePlans(userId: UserId): Set<Plan> {
        val now = timeProvider.now()
        val plans = subscriptionRepository.findActiveByUserId(userId, now).map { it.plan }.toSet()
        return plans.ifEmpty { setOf(Plan.FREE) }
    }
}
