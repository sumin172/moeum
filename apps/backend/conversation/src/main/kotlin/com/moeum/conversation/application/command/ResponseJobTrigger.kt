package com.moeum.conversation.application.command

import com.moeum.conversation.domain.model.ResponseJobId
import com.moeum.platform.job.WorkerProperties
import org.springframework.stereotype.Component

// 메시지 저장·사용자 재시도 직후 응답 작업을 바로 한 번 실행하도록 요청한다(지연 최소화).
// API 전용 인스턴스(moeum.worker.enabled=false)에서는 실행하지 않는다 — LLM 부하를 API 프로세스에서 떼어내고,
// 쌓인 작업은 워커 인스턴스의 poller가 가져간다(분리 운영 시 poll 주기를 1~2초로 줄인다).
@Component
class ResponseJobTrigger(
    private val responseJobExecutor: ResponseJobExecutor,
    private val workerProperties: WorkerProperties,
) {
    fun requestImmediateExecution(jobId: ResponseJobId) {
        if (workerProperties.enabled) {
            responseJobExecutor.executeAsync(jobId)
        }
    }
}
