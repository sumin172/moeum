package com.moeum.journal.interfaces

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.journal.application.command.ConfirmJournalService
import com.moeum.journal.application.command.EditJournalService
import com.moeum.journal.application.command.RetryJournalGenerationService
import com.moeum.journal.application.query.GetJournalService
import com.moeum.journal.interfaces.dto.ConfirmJournalRequest
import com.moeum.journal.interfaces.dto.EditJournalRequest
import com.moeum.journal.interfaces.dto.GenerationStatusResponse
import com.moeum.journal.interfaces.dto.JournalDayResponse
import com.moeum.journal.interfaces.dto.JournalResponse
import com.moeum.kernel.UserId
import com.moeum.kernel.UuidV7
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

// diaryDate는 사용자 하루 경계 기준 하루(대화 조회 응답의 dayDate와 같은 값)
@RestController
@RequestMapping("/api/journals")
class JournalController(
    private val getJournalService: GetJournalService,
    private val editJournalService: EditJournalService,
    private val confirmJournalService: ConfirmJournalService,
    private val retryJournalGenerationService: RetryJournalGenerationService,
    private val featureAccessQuery: FeatureAccessQuery,
) {
    @GetMapping("/{diaryDate}")
    fun getDay(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) diaryDate: LocalDate,
    ): JournalDayResponse = JournalDayResponse.from(getJournalService.getDay(userId, diaryDate))

    @PutMapping("/{diaryDate}")
    fun edit(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) diaryDate: LocalDate,
        @RequestBody request: EditJournalRequest,
    ): JournalResponse =
        // 수정은 JOURNAL_EDIT 권한이 있어야만 성공하므로 응답의 editable은 항상 true
        JournalResponse.from(editJournalService.edit(userId, diaryDate, request.title, request.body, request.version), editable = true)

    @PostMapping("/{diaryDate}/confirm")
    fun confirm(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) diaryDate: LocalDate,
        @RequestBody request: ConfirmJournalRequest,
    ): JournalResponse {
        // 이 요청이 확정 → JournalConfirmed로 이어지는 흐름의 시작이다
        val confirmed = confirmJournalService.confirm(userId, diaryDate, request.version, correlationId = UuidV7.generate())
        return JournalResponse.from(confirmed, featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT))
    }

    @PostMapping("/{diaryDate}/generation-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryGeneration(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) diaryDate: LocalDate,
    ): GenerationStatusResponse = GenerationStatusResponse.from(retryJournalGenerationService.retry(userId, diaryDate))
}
