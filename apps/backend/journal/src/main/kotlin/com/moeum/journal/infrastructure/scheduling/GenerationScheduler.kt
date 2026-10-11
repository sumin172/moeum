package com.moeum.journal.infrastructure.scheduling

import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.GenerationPlanner
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.moeum.platform.runtime.WorkerPool
import org.springframework.beans.factory.annotation.Qualifier
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 계획 조회와 실행 dispatch의 스케줄러를 분리한다. 긴 LLM 호출은 실행 슬롯 안에서 수행한다.
@Component
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class GenerationScheduler(
    private val generationPlanner: GenerationPlanner,
    private val generationExecutor: GenerationExecutor,
    @Qualifier("journalWorkerPool") private val pool: WorkerPool,
) {
    private val log = LoggerFactory.getLogger(GenerationScheduler::class.java)

    @Scheduled(
        scheduler = "journalPlanningScheduler", fixedDelayString = "PT5M",
        initialDelayString = "\${moeum.journal.planning.initial-delay:PT0S}",
    )
    fun planRecent() {
        runCatching { generationPlanner.planRecent() }
            .onFailure { e -> log.error("Journal 생성 Planning(RECENT) 실패", e) }
    }

    @Scheduled(scheduler = "journalPlanningScheduler", cron = "0 0 4 * * *")
    fun planReconcile() {
        runCatching { generationPlanner.planReconcile() }
            .onFailure { e -> log.error("Journal 생성 Planning(RECONCILIATION) 실패", e) }
    }

    @Scheduled(
        scheduler = "journalPollScheduler",
        fixedDelayString = "\${moeum.journal.generation-job.poll-interval:PT1S}",
        initialDelayString = "\${moeum.journal.generation-job.initial-delay:PT0S}",
    )
    fun pollDueJobs() {
        repeat(pool.concurrency) {
            if (!pool.submit { generationExecutor.executeNext() }) return
        }
    }
}
