package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalGenerator
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.TimeProvider
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.moeum.platform.observability.SafeFailure
import com.moeum.platform.observability.WorkTrace
import com.moeum.platform.job.JobFailure
import com.moeum.platform.job.JobFailureKind
import com.moeum.journal.infrastructure.ai.JournalGenerationException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

// 실행 슬롯 안에서 한 작업을 선점한다. 리스는 실제 실행 시점에 시작되고,
// 여러 인스턴스가 동시에 돌아도 SKIP LOCKED로 같은 작업을 잡지 않는다.
// LLM 호출은 트랜잭션 밖에서 하고, 결과 저장/실패 기록만 각각 별도 트랜잭션으로 한다.
// 리스가 만료된 작업은 실행 슬롯과 별개로 주기적으로 FAILED 정리한다(failExpiredLeases).
@Service
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class GenerationExecutor(
    private val generationJobRepository: GenerationJobRepository,
    private val recordDayOperations: RecordDayOperations,
    private val journalGenerator: JournalGenerator,
    private val saveGeneratedJournalService: SaveGeneratedJournalService,
    private val markGenerationFailedService: MarkGenerationFailedService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(GenerationExecutor::class.java)

    fun executeNext(): Boolean {
        val job = generationJobRepository.claimNext(timeProvider.now()) ?: return false
        WorkTrace.run(job.id.value, job.recordDayId.value, job.state.attemptCount) { execute(job) }
        return true
    }

    private fun execute(job: GenerationJob) {
        try {
            val input = recordDayOperations.readJournalInput(job.userId, job.recordDayId)
            val generation = journalGenerator.generate(input.displayDate, input.messages)
            saveGeneratedJournalService.save(job, input.displayDate, generation)
            log.atInfo().addKeyValue("event", "job.completed").log("Worker job completed")
        } catch (_: OptimisticLockingFailureException) {
            log.warn("리스를 잃은 일기 생성 작업이라 결과를 버림(만료 정리 또는 재요청됨): jobId={}", job.id.value)
        } catch (e: Exception) {
            val failure = if (e is JournalGenerationException) JobFailure(JobFailureKind.INVALID_OUTPUT) else JobFailure.classify(e)
            log.atError().addKeyValue("event", "job.failed")
                .addKeyValue("errorCode", SafeFailure.code(e))
                .addKeyValue("retryKind", failure.kind.name)
                .addKeyValue("location", SafeFailure.location(e))
                .log("Worker job failed")
            recordFailure(job) { markGenerationFailedService.markFailed(job, failure) }
        }
    }

    // 리스가 만료된 작업을 FAILED로 정리한다. 정리 실패는 다음 주기에 다시 시도한다.
    fun failExpiredLeases() {
        runCatching { markGenerationFailedService.failExpired(timeProvider.now(), EXPIRY_BATCH_SIZE) }
            .onSuccess { jobs -> jobs.forEach { logLeaseExpired(it) } }
            .onFailure { e -> log.warn("리스 만료 일기 생성 작업 정리 실패: error={}", SafeFailure.code(e)) }
    }

    private fun logLeaseExpired(job: GenerationJob) {
        log.atWarn().addKeyValue("event", "job.lease_expired")
            .addKeyValue("jobId", job.id.value)
            .addKeyValue("recordDayId", job.recordDayId.value)
            .addKeyValue("attemptCount", job.state.attemptCount)
            .log("리스가 만료된 일기 생성 작업을 실패로 정리")
    }

    // 실패 기록 자체가 실패해도(리스 만료로 정리된 경우 포함) 로그는 남아야 한다.
    private fun recordFailure(job: GenerationJob, record: () -> Unit) {
        runCatching(record).onFailure { e ->
            log.warn("일기 생성 작업 실패 기록을 남기지 못함: jobId={}, error={}", job.id.value, SafeFailure.code(e))
        }
    }
}

private const val EXPIRY_BATCH_SIZE = 100
