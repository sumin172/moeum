package com.moeum.identity.infrastructure.config

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.domain.model.Plan
import org.springframework.boot.context.properties.ConfigurationProperties

// 요금제별로 열리는 기능. 요금제 구성은 코드가 아니라 이 설정으로 바꾼다.
@ConfigurationProperties(prefix = "moeum.features")
data class FeaturePlanProperties(
    val plans: Map<Plan, Set<Feature>> = mapOf(
        Plan.FREE to emptySet(),
        Plan.PREMIUM to setOf(Feature.JOURNAL_EDIT),
    ),
) {
    fun featuresOf(plan: Plan): Set<Feature> = plans[plan].orEmpty()
}
