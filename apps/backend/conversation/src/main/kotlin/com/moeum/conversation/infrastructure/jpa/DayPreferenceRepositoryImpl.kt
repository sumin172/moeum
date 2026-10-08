package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.model.DayPreference
import com.moeum.kernel.UserId
import org.springframework.stereotype.Component

@Component
class DayPreferenceRepositoryImpl(
    private val jpaRepository: DayPreferenceJpaRepository,
) : DayPreferenceRepository {

    override fun findByUserId(userId: UserId): DayPreference? =
        jpaRepository.findById(userId.value).map { it.toDomain() }.orElse(null)

    override fun save(preference: DayPreference): DayPreference =
        jpaRepository.save(preference.toEntity()).toDomain()
}

private fun DayPreferenceJpaEntity.toDomain(): DayPreference =
    DayPreference(
        userId = UserId(userId),
        dayStartTime = dayStartTime,
        pendingDayStartTime = pendingDayStartTime,
        pendingEffectiveFrom = pendingEffectiveFrom,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DayPreference.toEntity(): DayPreferenceJpaEntity =
    DayPreferenceJpaEntity(
        userId = userId.value,
        dayStartTime = dayStartTime,
        pendingDayStartTime = pendingDayStartTime,
        pendingEffectiveFrom = pendingEffectiveFrom,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
