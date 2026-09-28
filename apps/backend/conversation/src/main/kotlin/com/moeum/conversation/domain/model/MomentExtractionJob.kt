package com.moeum.conversation.domain.model

import java.time.Instant

enum class MomentExtractionJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

data class MomentExtractionJob(
    val id: MomentExtractionJobId,
    val conversationDayId: ConversationDayId,
    // ensureExtracted() 호출 시점에 읽은 ConversationDay의 revision. request_key 멱등성에 쓰인다.
    val sourceRevision: Long,
    val requestKey: String,
    val status: MomentExtractionJobStatus,
    val attemptCount: Int,
    val errorCode: String? = null,
    val createdAt: Instant,
) {
    fun completed(): MomentExtractionJob = copy(status = MomentExtractionJobStatus.COMPLETED)

    fun failed(errorCode: String): MomentExtractionJob =
        copy(status = MomentExtractionJobStatus.FAILED, attemptCount = attemptCount + 1, errorCode = errorCode)

    companion object {
        fun requestKeyOf(conversationDayId: ConversationDayId, sourceRevision: Long): String =
            "${conversationDayId.value}:$sourceRevision"

        fun pending(conversationDayId: ConversationDayId, sourceRevision: Long, now: Instant): MomentExtractionJob =
            MomentExtractionJob(
                id = MomentExtractionJobId.generate(),
                conversationDayId = conversationDayId,
                sourceRevision = sourceRevision,
                requestKey = requestKeyOf(conversationDayId, sourceRevision),
                status = MomentExtractionJobStatus.PENDING,
                attemptCount = 0,
                createdAt = now,
            )
    }
}
