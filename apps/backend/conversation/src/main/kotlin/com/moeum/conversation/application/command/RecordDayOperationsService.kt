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
        val day = records.claimDelivery(now, now.plus(Duration.ofMinutes(1))) ?: return null
        return RecordDelivery(day.id, day.userId, day.closedAt!!, day.deliveryVersion)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun acknowledge(delivery: RecordDelivery): Boolean =
        records.acknowledgeDelivery(delivery.userId, delivery.recordDayId, delivery.version)

    override fun readJournalInput(userId: UserId, recordDayId: RecordDayId): JournalInput {
        val day = records.find(userId, recordDayId) ?: throw RecordDayNotFoundException("기록이 없습니다")
        val closedAt = checkNotNull(day.closedAt) { "열린 기록은 일기 입력이 아닙니다" }
        val input = messages.findJournalInput(userId, recordDayId, day.sourceRevision, closedAt)
        return JournalInput(day.displayDate, input.map { MessageSnapshot(it.role.name, it.content, it.occurredAt) })
    }
}
