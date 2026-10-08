package com.moeum.conversation.infrastructure.scheduling

import com.moeum.conversation.application.command.ResponseJobExecutor
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 재시도 차례가 된 작업과, 워커가 죽어 리스가 만료된 작업을 회수한다. 정상 경로(메시지 저장 직후 실행)는
// 이 poller 없이도 동작하므로, 주기는 지연보다 회수 보장 관점에서 잡는다.
// moeum.worker.enabled=false인 인스턴스(API 전용)에서는 등록되지 않는다.
@Component
@ConditionalOnProperty(name = ["moeum.worker.enabled"], havingValue = "true", matchIfMissing = true)
class ResponseJobScheduler(
    private val responseJobExecutor: ResponseJobExecutor,
    private val responseJobProperties: ResponseJobProperties,
) {
    private val log = LoggerFactory.getLogger(ResponseJobScheduler::class.java)

    @Scheduled(fixedDelayString = "\${moeum.conversation.response-job.poll-interval:PT10S}")
    fun pollDueJobs() {
        runCatching {
            repeat(responseJobProperties.pollBatchSize) {
                val job = responseJobExecutor.claimNext() ?: return@runCatching
                responseJobExecutor.executeClaimedAsync(job)
            }
        }.onFailure { e -> log.error("AI 응답 작업 poll 실패", e) }
    }
}
