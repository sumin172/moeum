package com.moeum.conversation.application.command

import com.moeum.conversation.application.publicapi.JournalInput
import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.application.publicapi.RecordDelivery
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.RecordDayNotFoundException
import com.moeum.conversation.domain.RecordDayRepository
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

@Service
class RecordDayOperationsService(
    private val records: RecordDayRepository,
    private val messages: MessageRepository,
    private val time: TimeProvider,
) : RecordDayOperations {
    private val log = LoggerFactory.getLogger(javaClass)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun closeNext(): Boolean {
        val now = time.now()
        val day = records.claimDueClosure(now) ?: return false
        records.update(day.closeIfDue(now))
        log.info("기록 마감: recordDayId={}, closedAt={}", day.id.value, day.closeAt)
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun claimDelivery(): RecordDelivery? {
        val now = time.now()
        val day = records.claimDelivery(now, now.minus(DELIVERY_LEASE)) ?: return null
        return RecordDelivery(day.id, day.userId, day.closedAt!!)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun acknowledge(delivery: RecordDelivery): Boolean =
        records.acknowledgeDelivery(delivery.userId, delivery.recordDayId)

    override fun readJournalInput(userId: UserId, recordDayId: RecordDayId): JournalInput {
        val day = records.find(userId, recordDayId) ?: throw RecordDayNotFoundException("기록이 없습니다")
        val closedAt = checkNotNull(day.closedAt) { "열린 기록은 일기 입력이 아닙니다" }
        val input = messages.findJournalInput(userId, recordDayId, day.sourceRevision, closedAt)
        return JournalInput(day.displayDate, input.map { MessageSnapshot(it.role.name, it.content, it.occurredAt) })
    }
}

// 선점 후 이 시간 안에 확인되지 않은 전달은 다른 워커가 다시 선점한다. 등록은 멱등이라 재전달해도 작업은 하나다.
private val DELIVERY_LEASE: Duration = Duration.ofMinutes(1)
