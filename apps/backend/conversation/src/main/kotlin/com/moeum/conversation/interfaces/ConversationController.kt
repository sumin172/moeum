package com.moeum.conversation.interfaces
import com.moeum.platform.runtime.*
import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.query.RecordQueryService
import com.moeum.conversation.interfaces.dto.*
import com.moeum.kernel.*
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
// 접수 트랜잭션에서는 답변 경계를 조회하지 않는다. 상세 상태는 기록 조회로 확인한다. 정리된 작업이면 responseStatus는 null이다.
data class SaveMessageResponse(val recordDayId: UUID, val message: MessageResponse, val responseStatus: String?)
data class CloseTimeRequest(val closeTime: LocalTime)
@ConditionalOnRole(ExecutionRole.API)
@RestController
@RequestMapping("/api/conversations")
class ConversationController(private val saveMessageService: SaveMessageService, private val recordQueryService: RecordQueryService, private val retryResponseService: RetryResponseService,
    private val quota: ConversationQuotaService,
) {
    @PostMapping("/messages")
    fun saveMessage(@AuthenticationPrincipal userId: UserId, @RequestBody request: SaveMessageRequest): SaveMessageResponse =
        saveMessageService.save(userId, SaveMessageCommand(request.clientMessageId, request.content, request.occurredAt, request.timezone))
            .let { SaveMessageResponse(it.record.id.value, MessageResponse.from(it.message), it.responseJob?.state?.status?.name) }
    @PostMapping("/records/{recordDayId}/response-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retryResponse(@AuthenticationPrincipal userId: UserId, @PathVariable recordDayId: UUID): ResponseStateResponse {
        val id = RecordDayId(recordDayId)
        return ResponseStateResponse.from(recordQueryService.responseState(userId, id, retryResponseService.retry(userId, id)))
    }
    @GetMapping("/quota")
    fun quota(@AuthenticationPrincipal userId: UserId) = quota.current(userId)
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
