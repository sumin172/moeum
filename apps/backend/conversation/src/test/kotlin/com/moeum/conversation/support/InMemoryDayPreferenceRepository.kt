package com.moeum.conversation.support

import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.model.DayPreference
import com.moeum.kernel.UserId

class InMemoryDayPreferenceRepository : DayPreferenceRepository {
    val preferences = mutableMapOf<UserId, DayPreference>()

    override fun findByUserId(userId: UserId): DayPreference? = preferences[userId]

    override fun save(preference: DayPreference): DayPreference {
        preferences[preference.userId] = preference
        return preference
    }
}
