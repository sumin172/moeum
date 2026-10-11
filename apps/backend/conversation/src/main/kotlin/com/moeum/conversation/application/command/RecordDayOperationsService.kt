package com.moeum.conversation.application.command

import com.moeum.conversation.application.publicapi.FrozenRecordInput
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
        log.info("기록 마감: recordDayId={}, reason={}, closedAt={}", day.id.value, day.closeReason, day.closeAt)
        val state = records.findState(day.userId)!!
        records.saveState(state.copy(activeRecordDayId = null))
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun claimDelivery(): RecordDelivery? {
        val now = time.now()
        val day = records.claimDelivery(now, now.plus(Duration.ofMinutes(1))) ?: return null
        return RecordDelivery(day.id, day.userId, day.displayDate, day.closedAt!!, day.deliveryVersion)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun acknowledge(delivery: RecordDelivery): Boolean =
        records.acknowledgeDelivery(delivery.userId, delivery.recordDayId, delivery.version)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun freezeInput(userId: UserId, recordDayId: RecordDayId): FrozenRecordInput {
        records.lockState(userId)
        val day = records.find(userId, recordDayId, lock = true)
            ?: throw RecordDayNotFoundException("기록이 없습니다")
        val frozen = day.freeze()
        if (day.frozenSourceRevision == null) {
            records.update(frozen)
            log.info("기록 입력 동결: recordDayId={}, sourceRevision={}", day.id.value, frozen.frozenSourceRevision)
        }
        val sourceRevision = frozen.frozenSourceRevision!!
        // ASSISTANT는 늦게 완료될 수 있다. 불변 USER 원본과 확정 순번만 최초/재시도 입력으로 사용한다.
        val input = messages.findSource(userId, recordDayId, sourceRevision)
        return FrozenRecordInput(sourceRevision, input.map { MessageSnapshot(it.id.value, it.role.name, it.content, it.occurredAt) })
    }
}
