package com.moeum.conversation.interfaces
import com.moeum.platform.runtime.*
import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.query.RecordQueryService
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.interfaces.dto.*
import com.moeum.kernel.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
data class SaveMessageResponse(val recordDayId: UUID, val message: MessageResponse)
data class ResponseJobStatusResponse(val id: UUID, val userMessageId: UUID, val status: String, val failureReason: String?, val retryable: Boolean)
data class CloseTimeRequest(val closeTime: LocalTime)
@ConditionalOnRole(ExecutionRole.API)
@RestController
@RequestMapping("/api/conversations")
class ConversationController(private val saveMessageService: SaveMessageService, private val recordQueryService: RecordQueryService, private val retryResponseService: RetryResponseService,
    private val quota: ConversationQuotaService,
    private val statuses: com.moeum.conversation.application.query.ResponseStatusQueryService,
) {
    @PostMapping("/messages")
    fun saveMessage(@AuthenticationPrincipal userId: UserId, @RequestBody request: SaveMessageRequest): SaveMessageResponse =
        saveMessageService.save(userId, SaveMessageCommand(request.clientMessageId, request.content, request.occurredAt, request.timezone))
            .let { SaveMessageResponse(it.record.id.value, MessageResponse.from(it.message, it.responseJob)) }
    @PostMapping("/messages/{messageId}/response-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryResponse(@AuthenticationPrincipal userId: UserId, @PathVariable messageId: UUID): MessageResponse {
        val result = retryResponseService.retry(userId, MessageId(messageId))
        return MessageResponse.from(result.message, result.responseJob)
    }
    @GetMapping("/quota")
    fun quota(@AuthenticationPrincipal userId: UserId) = quota.current(userId)
    @GetMapping("/response-jobs")
    fun statuses(@AuthenticationPrincipal userId: UserId, @RequestParam ids: List<UUID>) =
        statuses.get(userId, ids.map { com.moeum.conversation.domain.model.ResponseJobId(it) })
            .map { ResponseJobStatusResponse(it.job.id.value,it.job.userMessageId.value,it.job.state.status.name,it.job.failureReason?.name,it.retryable) }
    @GetMapping("/records")
    fun records(@AuthenticationPrincipal userId: UserId, @RequestParam(required = false) date: LocalDate?, @RequestParam(defaultValue = "50") limit: Int) =
        recordQueryService.list(userId, date, limit.coerceIn(1, 200)).map { RecordSummaryResponse.from(it) }
    @GetMapping("/records/{recordDayId}")
    fun record(@AuthenticationPrincipal userId: UserId, @PathVariable recordDayId: UUID, @RequestParam(required = false) after: Long?, @RequestParam(defaultValue = "50") limit: Int) =
        RecordConversationResponse.from(recordQueryService.get(userId, RecordDayId(recordDayId), after, limit.coerceIn(1, 200)))
    @GetMapping("/preferences")
    fun preference(@AuthenticationPrincipal userId: UserId) = CloseTimeRequest(recordQueryService.closeTime(userId))
    @PutMapping("/preferences")
    fun preference(@AuthenticationPrincipal userId: UserId, @RequestBody request: CloseTimeRequest) =
        CloseTimeRequest(recordQueryService.changeCloseTime(userId, request.closeTime))
}
