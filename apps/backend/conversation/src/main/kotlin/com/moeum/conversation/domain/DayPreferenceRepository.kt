package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.DayPreference
import com.moeum.kernel.UserId
import java.time.Instant

interface DayPreferenceRepository {
    fun findByUserId(userId: UserId): DayPreference?
    fun save(preference: DayPreference): DayPreference
}

// 설정을 한 번도 바꾸지 않은 사용자는 row가 없다 — 기본값으로 계산하고, 변경 요청이 올 때 처음 저장한다.
fun DayPreferenceRepository.findOrDefault(userId: UserId, now: Instant): DayPreference =
    findByUserId(userId) ?: DayPreference.default(userId, now)
