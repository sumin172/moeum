package com.moeum.conversation.application.command

import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.RecordDayRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.parseTimezone
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.conversation.infrastructure.config.RecordDayProperties
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class SaveMessageCommand(
    val clientMessageId: UUID,
    val content: String,
    val occurredAt: Instant,
    val timezone: String,
)
data class SaveMessageResult(val message: Message, val responseJob: ResponseJob?, val record: RecordDay)

@Service
class SaveMessageService(
    private val recordDayRepository: RecordDayRepository,
    private val messageRepository: MessageRepository,
    private val responseJobRepository: ResponseJobRepository,
    private val quota: ConversationQuotaService,
    private val limits: ConversationLimitProperties,
    private val recordDayProperties: RecordDayProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(SaveMessageService::class.java)

    @Transactional
    fun save(userId: UserId, command: SaveMessageCommand): SaveMessageResult {
        if (command.content.isBlank() || command.content.length > limits.maxMessageLength) {
            throw InvalidConversationRequestException("메시지 길이가 유효하지 않습니다")
        }
        val zone = parseTimezone(command.timezone)
        val state = recordDayRepository.lockState(userId)
        // 사용자 상태 잠금으로 동시 중복을 직렬화한다. 유니크 제약 예외를 포괄적으로 삼키지 않는다.
        messageRepository.findByUserIdAndClientMessageId(userId, command.clientMessageId)?.let { existing ->
            val record = recordDayRepository.find(userId, existing.recordDayId) ?: error("메시지의 기록이 없습니다")
            return SaveMessageResult(existing, responseJobRepository.findByRecord(userId, record.id), record)
        }
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val occurredAt = command.occurredAt.truncatedTo(ChronoUnit.MICROS)
        if (occurredAt > now.plus(Duration.ofMinutes(5))) {
            throw InvalidConversationRequestException("미래 발화 허용 범위를 초과했습니다")
        }
        val active = recordDayRepository.findOpen(userId, lock = true)?.prepareForMessage(zone, now)
        if (active?.closedAt != null) {
            recordDayRepository.update(active)
            log.info("기록 마감: recordDayId={}, closedAt={}", active.id.value, active.closedAt)
        }
        val created = active == null || active.closedAt != null
        var record = if (created) RecordDay.create(userId, now, zone, state.closeTime,
            recordDayProperties.eveningStart, recordDayProperties.morningEnd,
            active ?: recordDayRepository.latestClosed(userId)) else active

        quota.consume(userId, record, now)
        record = record.append()
        if (created) recordDayRepository.insert(record) else recordDayRepository.update(record)
        val message = Message.userMessage(
            id = MessageId.generate(), userId = userId, content = command.content,
            occurredAt = occurredAt,
            recordDayId = record.id, sourceSequence = record.sourceRevision,
            clientMessageId = command.clientMessageId, now = now,
        )
        val saved = messageRepository.append(message)
        // 기록 잠금 아래에서 응답 실행 상태를 갱신한다. 대기·실행 중이면 그 실행이 끝난 뒤 이 입력까지 이어서 답한다.
        val current = if (created) null else responseJobRepository.findByRecord(userId, record.id)
        val job = when {
            current == null -> ResponseJob.pending(userId, record.id, now).also(responseJobRepository::insert)
            else -> current.inputAccepted(now)?.let(responseJobRepository::save) ?: current
        }
        return SaveMessageResult(saved, job, record)
    }
}
