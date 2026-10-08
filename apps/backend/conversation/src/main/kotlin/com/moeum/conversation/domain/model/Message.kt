package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class MessageRole { USER, ASSISTANT }

enum class MessageResponseStatus { PENDING, PROCESSING, COMPLETED, FAILED }

data class Message(
    val id: MessageId,
    val userId: UserId,
    val role: MessageRole,
    val content: String,
    val occurredAt: Instant,
    val timezone: String,
    // 달력상 현지 날짜(원본 사실). 하루 단위 묶음은 dayDate를 쓴다.
    val localDate: LocalDate,
    // 사용자 하루 경계(DayPreference)로 계산한 논리적 하루. 저장 시점에 확정되고 이후 재계산하지 않는다.
    val dayDate: LocalDate,
    val clientMessageId: UUID?,
    val responseStatus: MessageResponseStatus?,
    val generationId: UUID? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    // 서버 저장 시각. 오프라인 지연 전송이면 occurredAt보다 한참 뒤일 수 있다.
    val createdAt: Instant,
    val deletedAt: Instant? = null,
) {
    fun withResponseStatus(status: MessageResponseStatus): Message = copy(responseStatus = status)

    companion object {
        fun userMessage(
            id: MessageId,
            userId: UserId,
            content: String,
            occurredAt: Instant,
            timezone: String,
            localDate: LocalDate,
            dayDate: LocalDate,
            clientMessageId: UUID,
            now: Instant,
        ): Message =
            Message(
                id = id,
                userId = userId,
                role = MessageRole.USER,
                content = content,
                occurredAt = occurredAt,
                timezone = timezone,
                localDate = localDate,
                dayDate = dayDate,
                clientMessageId = clientMessageId,
                responseStatus = MessageResponseStatus.PENDING,
                createdAt = now,
            )

        fun assistantMessage(
            id: MessageId,
            userId: UserId,
            content: String,
            occurredAt: Instant,
            timezone: String,
            localDate: LocalDate,
            dayDate: LocalDate,
            generationId: UUID,
            model: String,
            promptVersion: String,
            inputTokens: Int,
            outputTokens: Int,
        ): Message =
            Message(
                id = id,
                userId = userId,
                role = MessageRole.ASSISTANT,
                content = content,
                occurredAt = occurredAt,
                timezone = timezone,
                localDate = localDate,
                dayDate = dayDate,
                clientMessageId = null,
                responseStatus = null,
                generationId = generationId,
                model = model,
                promptVersion = promptVersion,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                createdAt = occurredAt,
            )
    }
}
