package com.moeum.conversation.interfaces

import com.moeum.conversation.application.command.ChangeDayStartTimeService
import com.moeum.conversation.application.query.GetDayPreferenceService
import com.moeum.conversation.interfaces.dto.ChangeDayStartTimeRequest
import com.moeum.conversation.interfaces.dto.DayPreferenceResponse
import com.moeum.kernel.UserId
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/conversations/day-preference")
class DayPreferenceController(
    private val getDayPreferenceService: GetDayPreferenceService,
    private val changeDayStartTimeService: ChangeDayStartTimeService,
) {
    @GetMapping
    fun get(@AuthenticationPrincipal userId: UserId): DayPreferenceResponse =
        DayPreferenceResponse.from(getDayPreferenceService.get(userId))

    @PutMapping("/day-start-time")
    fun changeDayStartTime(
        @AuthenticationPrincipal userId: UserId,
        @RequestBody request: ChangeDayStartTimeRequest,
    ): DayPreferenceResponse =
        DayPreferenceResponse.from(changeDayStartTimeService.change(userId, request.dayStartTime, request.timezone))
}
