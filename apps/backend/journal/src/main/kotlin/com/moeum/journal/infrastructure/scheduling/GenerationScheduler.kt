package com.moeum.journal.infrastructure.scheduling

import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.GenerationPlanner
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 일기 생성 Planner/Executor의 실행 주기. moeum.worker.enabled=false인 인스턴스(API 전용)에서는 등록되지 않는다.
@Component
@ConditionalOnProperty(name = ["moeum.worker.enabled"], havingValue = "true", matchIfMissing = true)
class GenerationScheduler(
    private val generationPlanner: GenerationPlanner,
    private val generationExecutor: GenerationExecutor,
) {
    private val log = LoggerFactory.getLogger(GenerationScheduler::class.java)

    @Scheduled(fixedDelayString = "PT5M")
    fun planRecent() {
        runCatching { generationPlanner.planRecent() }
            .onFailure { e -> log.error("Journal 생성 Planning(RECENT) 실패", e) }
    }

    @Scheduled(cron = "0 0 4 * * *")
    fun planReconcile() {
        runCatching { generationPlanner.planReconcile() }
            .onFailure { e -> log.error("Journal 생성 Planning(RECONCILIATION) 실패", e) }
    }

    @Scheduled(fixedDelayString = "PT1M")
    fun executeDueJobs() {
        runCatching { generationExecutor.executeDueJobs() }
            .onFailure { e -> log.error("Journal 생성 Execution 실패", e) }
    }
}
