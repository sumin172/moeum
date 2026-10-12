package com.moeum.conversation.application.command

import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.RecordDayRepository
import com.moeum.conversation.domain.RecordDayNotFoundException
import com.moeum.conversation.domain.RecordAssignmentConflictException
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.parseTimezone
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.kernel.RecordDayId
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
    val recordDayId: RecordDayId? = null,
)
sealed interface SaveMessageResult {
    data class Accepted(val message: Message, val responseJob: ResponseJob?, val record: RecordDay) : SaveMessageResult
    data class Discarded(val recordDayId: RecordDayId) : SaveMessageResult
}

@Service
class SaveMessageService(
    private val recordDayRepository: RecordDayRepository,
    private val messageRepository: MessageRepository,
    private val responseJobRepository: ResponseJobRepository,
    private val quota: ConversationQuotaService,
    private val limits: ConversationLimitProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(SaveMessageService::class.java)

    @Transactional
    fun save(userId: UserId, command: SaveMessageCommand): SaveMessageResult {
        if (command.content.isBlank() || command.content.length > limits.maxMessageLength) {
            throw InvalidConversationRequestException("메시지 길이가 유효하지 않습니다")
        }
        val zone = parseTimezone(command.timezone)
        val now = timeProvider.now().truncatedTo(ChronoUnit.MICROS)
        val occurredAt = command.occurredAt.truncatedTo(ChronoUnit.MICROS)
        if (occurredAt > now.plus(Duration.ofMinutes(5))) {
            throw InvalidConversationRequestException("미래 발화 허용 범위를 초과했습니다")
        }
        var state = recordDayRepository.lockState(userId)
        // 사용자 상태 잠금으로 동시 중복을 직렬화한다. 유니크 제약 예외를 포괄적으로 삼키지 않는다.
        messageRepository.findByUserIdAndClientMessageId(userId, command.clientMessageId)?.let { existing ->
            if (command.recordDayId != null && command.recordDayId != existing.recordDayId) {
                throw RecordAssignmentConflictException("재전송 기록 식별자가 원본과 다릅니다")
            }
            val record = recordDayRepository.find(userId, existing.recordDayId) ?: error("메시지의 기록이 없습니다")
            return SaveMessageResult.Accepted(existing,
                responseJobRepository.findByUserIdAndUserMessageId(userId, existing.id), record)
        }
        val active = state.activeRecordDayId?.let { recordDayRepository.find(userId, it, lock = true)!! }
        if (active != null && active.closedAt == null && now >= active.closeAt) {
            recordDayRepository.update(active.closeIfDue(now))
            state = state.copy(activeRecordDayId = null)
            recordDayRepository.saveState(state)
        }
        var created = false
        var record = if (command.recordDayId != null) {
            recordDayRepository.find(userId, command.recordDayId, lock = true)
                ?: throw RecordDayNotFoundException("기록이 없습니다")
        } else {
            val candidates = recordDayRepository.findContaining(userId, occurredAt)
            if (candidates.size > 1) throw RecordAssignmentConflictException("기록 구간이 모호합니다")
            candidates.singleOrNull()?.let { recordDayRepository.find(userId, it.id, lock = true)!! }
                ?: run {
                    val next = RecordDay.create(userId, occurredAt, zone, state.closeTime,
                        recordDayRepository.latestClosedBoundary(userId, occurredAt)).closeIfDue(now)
                    if (recordDayRepository.overlaps(userId, next.startAt, next.closedAt ?: next.closeAt)) {
                        throw RecordAssignmentConflictException("기존 기록 구간과 겹칩니다")
                    }
                    if (next.closedAt == null && state.activeRecordDayId != null) {
                        throw RecordAssignmentConflictException("이미 다른 열린 기록이 있습니다")
                    }
                    created = true
                    next
                }
        }
        if (!record.contains(occurredAt)) throw RecordAssignmentConflictException("발화 시각이 기록 구간 밖입니다")
        if (record.frozenSourceRevision != null) return SaveMessageResult.Discarded(record.id)

        quota.consume(userId, timeProvider.now())
        record = record.append(occurredAt, zone, now)
        if (created) recordDayRepository.insert(record) else recordDayRepository.update(record)
        if (created && record.closedAt == null) {
            state = state.copy(activeRecordDayId = record.id)
            recordDayRepository.saveState(state)
        } else if (record.closedAt != null && state.activeRecordDayId == record.id) {
            recordDayRepository.saveState(state.copy(activeRecordDayId = null))
        }
        if (record.closedAt != null) {
            log.info("기록 마감: recordDayId={}, reason={}, closedAt={}", record.id.value, record.closeReason, record.closedAt)
        }
        val message = Message.userMessage(
            id = MessageId.generate(), userId = userId, content = command.content,
            occurredAt = occurredAt, timezone = zone.id,
            recordDayId = record.id, sourceSequence = record.sourceRevision,
            clientMessageId = command.clientMessageId, now = now,
        )
        val saved = messageRepository.append(message)
        // 닫힌 과거 기록에는 AI 응답을 만들지 않는다.
        val job = if (record.closedAt != null) null else responseJobRepository.save(ResponseJob.pending(saved, now))
        return SaveMessageResult.Accepted(saved, job, record)
    }
}
