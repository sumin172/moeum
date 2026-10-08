package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.DiaryPreferenceRepository
import com.moeum.journal.domain.model.DiaryPreference
import com.moeum.kernel.UserId
import org.springframework.stereotype.Component

@Component
class DiaryPreferenceRepositoryImpl(
    private val jpaRepository: DiaryPreferenceJpaRepository,
) : DiaryPreferenceRepository {

    override fun findByUserId(userId: UserId): DiaryPreference? =
        jpaRepository.findById(userId.value).map { it.toDomain() }.orElse(null)

    override fun save(preference: DiaryPreference): DiaryPreference =
        jpaRepository.save(preference.toEntity()).toDomain()
}

private fun DiaryPreferenceJpaEntity.toDomain(): DiaryPreference =
    DiaryPreference(
        userId = UserId(userId),
        generationTime = generationTime,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun DiaryPreference.toEntity(): DiaryPreferenceJpaEntity =
    DiaryPreferenceJpaEntity(
        userId = userId.value,
        generationTime = generationTime,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
