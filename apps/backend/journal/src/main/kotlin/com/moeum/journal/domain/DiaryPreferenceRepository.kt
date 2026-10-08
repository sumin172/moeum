package com.moeum.journal.domain

import com.moeum.journal.domain.model.DiaryPreference
import com.moeum.kernel.UserId

interface DiaryPreferenceRepository {
    fun findByUserId(userId: UserId): DiaryPreference?
    fun save(preference: DiaryPreference): DiaryPreference
}
