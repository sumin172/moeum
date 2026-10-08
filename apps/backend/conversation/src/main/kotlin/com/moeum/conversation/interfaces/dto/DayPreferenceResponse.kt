package com.moeum.conversation.interfaces.dto

import com.moeum.conversation.domain.model.DayPreference
import java.time.LocalDate
import java.time.LocalTime

data class DayPreferenceResponse(
    val dayStartTime: LocalTime,
    // 예약된 변경. pendingEffectiveFrom 하루부터 pendingDayStartTime이 적용된다.
    val pendingDayStartTime: LocalTime?,
    val pendingEffectiveFrom: LocalDate?,
) {
    companion object {
        fun from(preference: DayPreference): DayPreferenceResponse =
            DayPreferenceResponse(
                dayStartTime = preference.dayStartTime,
                pendingDayStartTime = preference.pendingDayStartTime,
                pendingEffectiveFrom = preference.pendingEffectiveFrom,
            )
    }
}
