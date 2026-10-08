package com.moeum.conversation.application.query

import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.findOrDefault
import com.moeum.conversation.domain.model.DayPreference
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service

@Service
class GetDayPreferenceService(
    private val dayPreferenceRepository: DayPreferenceRepository,
    private val timeProvider: TimeProvider,
) {
    fun get(userId: UserId): DayPreference = dayPreferenceRepository.findOrDefault(userId, timeProvider.now())
}
