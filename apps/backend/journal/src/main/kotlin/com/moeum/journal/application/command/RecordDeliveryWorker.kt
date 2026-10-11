package com.moeum.journal.application.command
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.platform.runtime.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
@Service
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class RecordDeliveryWorker(private val records: RecordDayOperations, private val registration: RegisterRecordGenerationService) {
    private val log = LoggerFactory.getLogger(javaClass)
    fun runBatch() {
        for (i in 0 until 100) { if (!records.closeNext()) break }
        repeat(100) {
            val delivery = records.claimDelivery() ?: return
            try {
                val created = registration.register(delivery)
                val acknowledged = records.acknowledge(delivery)
                log.info("기록 전달: recordDayId={}, version={}, registered={}, acknowledged={}",
                    delivery.recordDayId.value, delivery.version, created, acknowledged)
            } catch (e: Exception) {
                log.error("기록 전달 실패: recordDayId={}, version={}", delivery.recordDayId.value, delivery.version, e)
                // 리스 만료 후 재전달한다. 다른 기록은 계속 처리한다.
            }
        }
    }
}
