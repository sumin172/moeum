package com.moeum.journal.application.command
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.platform.runtime.*
import com.moeum.platform.observability.SafeFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
@Service
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class RecordDeliveryWorker(private val records: RecordDayOperations, private val registration: RegisterRecordGenerationService) {
    private val log = LoggerFactory.getLogger(javaClass)
    fun runBatch() {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos()
        repeat(5) {
            var closedCount = 0
            while (closedCount < 100 && records.closeNext()) closedCount++
            var deliveredCount = 0
            while (deliveredCount < 100) {
                val delivery = records.claimDelivery() ?: break
                deliveredCount++
                try {
                    val created = registration.register(delivery)
                    val acknowledged = records.acknowledge(delivery)
                    log.info("기록 전달: recordDayId={}, version={}, registered={}, acknowledged={}",
                        delivery.recordDayId.value, delivery.version, created, acknowledged)
                } catch (e: Exception) {
                    log.error("기록 전달 실패: recordDayId={}, version={}, code={}", delivery.recordDayId.value, delivery.version, SafeFailure.code(e))
                    // 리스 만료 후 재전달한다. 다른 기록은 계속 처리한다.
                }
            }
            if ((closedCount < 100 && deliveredCount < 100) || System.nanoTime() >= deadline) return
        }
    }
}
