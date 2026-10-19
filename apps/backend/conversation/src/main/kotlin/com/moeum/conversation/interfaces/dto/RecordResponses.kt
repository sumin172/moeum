package com.moeum.conversation.interfaces.dto
import com.moeum.conversation.application.query.RecordMessages
import com.moeum.conversation.domain.model.RecordDay
import java.time.*
import java.util.UUID
data class RecordSummaryResponse(val recordDayId: UUID, val displayDate: LocalDate, val startAt: Instant,
    val closeAt: Instant, val closedAt: Instant?) {
    companion object {
        fun from(record: RecordDay) = RecordSummaryResponse(record.id.value,record.displayDate,record.startAt,record.closeAt,record.closedAt)
    }
}
data class RecordConversationResponse(val record: RecordSummaryResponse, val messages: List<MessageResponse>, val nextCursor: Long?) {
    companion object {
        fun from(result: RecordMessages) = RecordConversationResponse(RecordSummaryResponse.from(result.record),
            result.messages.map { MessageResponse.from(it,result.responseJobs[it.id],result.responseJobs[it.id]?.let { job -> job.retryable && job.id !in result.supersededIds }) }, result.nextCursor)
    }
}
