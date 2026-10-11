package com.moeum.journal.interfaces

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

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
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate
import java.util.UUID
import com.moeum.kernel.RecordDayId

// recordDayId로 일기를 식별하고 displayDate는 목록 필터에 사용한다.
@ConditionalOnRole(ExecutionRole.API)
@RestController
@RequestMapping("/api/journals")
class JournalController(
    private val getJournalService: GetJournalService,
    private val editJournalService: EditJournalService,
    private val confirmJournalService: ConfirmJournalService,
    private val retryJournalGenerationService: RetryJournalGenerationService,
    private val featureAccessQuery: FeatureAccessQuery,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal userId: UserId, @RequestParam date: LocalDate): List<JournalResponse> {
        val editable = featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT)
        return getJournalService.list(userId, date).map { JournalResponse.from(it, editable) }
    }
    @GetMapping("/{recordDayId}")
    fun getDay(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable recordDayId: UUID,
    ): JournalDayResponse = JournalDayResponse.from(getJournalService.getDay(userId, RecordDayId(recordDayId)))

    @PutMapping("/{recordDayId}")
    fun edit(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable recordDayId: UUID,
        @RequestBody request: EditJournalRequest,
    ): JournalResponse =
        // 수정은 JOURNAL_EDIT 권한이 있어야만 성공하므로 응답의 editable은 항상 true
        JournalResponse.from(editJournalService.edit(userId, RecordDayId(recordDayId), request.title, request.body, request.version), editable = true)

    @PostMapping("/{recordDayId}/confirm")
    fun confirm(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable recordDayId: UUID,
        @RequestBody request: ConfirmJournalRequest,
    ): JournalResponse {
        // 이 요청이 확정 → JournalConfirmed로 이어지는 흐름의 시작이다
        val confirmed = confirmJournalService.confirm(userId, RecordDayId(recordDayId), request.version, correlationId = UuidV7.generate())
        return JournalResponse.from(confirmed, featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT))
    }

    @PostMapping("/{recordDayId}/generation-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryGeneration(
        @AuthenticationPrincipal userId: UserId,
        @PathVariable recordDayId: UUID,
    ): GenerationStatusResponse = GenerationStatusResponse.from(retryJournalGenerationService.retry(userId, RecordDayId(recordDayId)))
}
