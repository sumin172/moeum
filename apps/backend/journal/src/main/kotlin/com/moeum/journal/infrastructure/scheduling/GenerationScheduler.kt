package com.moeum.journal.infrastructure.scheduling
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.RecordDeliveryWorker
import com.moeum.platform.runtime.*
import org.springframework.beans.factory.annotation.Qualifier
import com.moeum.platform.observability.SafeFailure
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
@Component
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class GenerationScheduler(private val delivery: RecordDeliveryWorker, private val generationExecutor: GenerationExecutor,
    @Qualifier("journalWorkerPool") private val pool: WorkerPool) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(scheduler = "journalDeliveryScheduler", fixedDelayString = $$"${moeum.journal.delivery.interval:PT5S}",
        initialDelayString = $$"${moeum.journal.delivery.initial-delay:PT0S}"
    )
    fun deliverClosedRecords() {
        runCatching { delivery.runBatch() }.onFailure { log.error("기록 마감·전달 실패: code={}", SafeFailure.code(it)) }
    }
    @Scheduled(scheduler = "journalPollScheduler",
        fixedDelayString = $$"${moeum.journal.generation-job.poll-interval:PT1S}",
        initialDelayString = $$"${moeum.journal.generation-job.initial-delay:PT0S}"
    )
    fun pollDueJobs() {
        repeat(pool.concurrency) { if (!pool.submit { generationExecutor.executeNext() }) return }
    }
}
