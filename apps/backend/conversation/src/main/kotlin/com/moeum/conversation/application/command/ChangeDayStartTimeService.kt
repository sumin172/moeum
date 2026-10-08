package com.moeum.conversation.application.command

import com.moeum.conversation.domain.DayPreferenceRepository
import com.moeum.conversation.domain.findOrDefault
import com.moeum.conversation.domain.model.DayPreference
import com.moeum.conversation.domain.parseTimezone
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalTime
import java.time.temporal.ChronoUnit

@Service
class ChangeDayStartTimeService(
    private val dayPreferenceRepository: DayPreferenceRepository,
    private val timeProvider: TimeProvider,
) {
    // 변경은 사용자가 지금 있는 timezone 기준 "내일" 하루부터 적용된다.
    @Transactional
    fun change(userId: UserId, dayStartTime: LocalTime, timezone: String): DayPreference {
        val zoneId = parseTimezone(timezone)
        val now = timeProvider.now()
        val current = dayPreferenceRepository.findOrDefault(userId, now)
        val changed = current.requestChange(
            newDayStartTime = dayStartTime.truncatedTo(ChronoUnit.MINUTES),
            today = current.dayDateOf(now, zoneId),
            now = now,
        )
        return dayPreferenceRepository.save(changed)
    }
}
