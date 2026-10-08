package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalGenerator
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.infrastructure.config.GenerationJobProperties
import com.moeum.kernel.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

// 실행할 차례인 일기 생성 작업을 하나씩 claim해서 순서대로 처리한다. 하나씩 잡으므로 리스는 작업마다 새로 시작되고,
// 여러 인스턴스가 동시에 돌아도 SKIP LOCKED로 같은 작업을 잡지 않는다.
// LLM 호출은 트랜잭션 밖에서 하고, 결과 저장/실패 기록만 각각 별도 트랜잭션으로 한다.
@Service
class GenerationExecutor(
    private val generationJobRepository: GenerationJobRepository,
    private val conversationActivityQuery: ConversationActivityQuery,
    private val journalGenerator: JournalGenerator,
    private val saveGeneratedJournalService: SaveGeneratedJournalService,
    private val markGenerationFailedService: MarkGenerationFailedService,
    private val generationJobProperties: GenerationJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(GenerationExecutor::class.java)
    private val policy = generationJobProperties.toPolicy()

    fun executeDueJobs() {
        repeat(generationJobProperties.maxJobsPerRun) {
            val now = timeProvider.now()
            val job = generationJobRepository.claimNext(now, policy.leaseExpiresAt(now)) ?: return
            execute(job)
        }
    }

    private fun execute(job: GenerationJob) {
        if (policy.isExhausted(job.state.attemptCount)) {
            log.warn("리스 만료로 재시도 횟수를 넘긴 일기 생성 작업을 최종 실패 처리: jobId={}", job.id.value)
            recordFailure(job) { markGenerationFailedService.markExhausted(job) }
            return
        }

        try {
            val messages = conversationActivityQuery.findMessages(job.userId, job.diaryDate)
            val generation = journalGenerator.generate(job.userId, job.diaryDate, messages)
            saveGeneratedJournalService.save(job, generation)
        } catch (_: OptimisticLockingFailureException) {
            log.warn("리스를 잃은 일기 생성 작업이라 결과를 버림(다른 워커가 처리 중): jobId={}", job.id.value)
        } catch (e: Exception) {
            log.error(
                "Journal 생성 실패: jobId={}, userId={}, diaryDate={}, attempt={}, error={}",
                job.id.value, job.userId.value, job.diaryDate, job.state.attemptCount, e.message, e,
            )
            recordFailure(job) { markGenerationFailedService.markFailed(job, e) }
        }
    }

    // 실패 기록 자체가 실패해도(리스를 뺏긴 경우 포함) 로그는 남아야 한다.
    private fun recordFailure(job: GenerationJob, record: () -> Unit) {
        runCatching(record).onFailure { e ->
            log.warn("일기 생성 작업 실패 기록을 남기지 못함: jobId={}, error={}", job.id.value, e.message)
        }
    }
}
