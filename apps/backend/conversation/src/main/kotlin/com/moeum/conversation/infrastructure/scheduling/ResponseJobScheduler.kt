package com.moeum.conversation.infrastructure.scheduling

import com.moeum.conversation.application.command.ResponseJobExecutor
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.moeum.platform.runtime.WorkerPool
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 슬롯 확보 후 워커 스레드 안에서 선점한다. 스케줄러는 LLM 호출을 기다리지 않는다.
@Component
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
class ResponseJobScheduler(
    private val responseJobExecutor: ResponseJobExecutor,
    @Qualifier("conversationWorkerPool") private val pool: WorkerPool,
) {
    @Scheduled(
        scheduler = "conversationPollScheduler",
        fixedDelayString = $$"${moeum.conversation.response-job.poll-interval:PT1S}",
        initialDelayString = $$"${moeum.conversation.response-job.initial-delay:PT0S}",
    )
    fun pollDueJobs() {
        if (!responseJobExecutor.canPoll()) return
        pool.submitBatch(responseJobExecutor.claimBatchSize,responseJobExecutor::claim,responseJobExecutor::execute)
    }
}
