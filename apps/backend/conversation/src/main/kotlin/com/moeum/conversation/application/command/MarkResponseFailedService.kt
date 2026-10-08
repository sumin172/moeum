package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED"

@Service
class MarkResponseFailedService(
    private val responseJobRepository: ResponseJobRepository,
    responseJobProperties: ResponseJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val policy = responseJobProperties.toPolicy()

    // 재시도가 남았으면 다음 시도를 예약하고, 소진됐으면 최종 실패로 둔다.
    @Transactional
    fun markFailed(job: ResponseJob, cause: Throwable) {
        responseJobRepository.save(job.failed(cause::class.simpleName ?: "UNKNOWN", policy, timeProvider.now()))
    }

    @Transactional
    fun markExhausted(job: ResponseJob) {
        responseJobRepository.save(job.failedPermanently(ATTEMPTS_EXHAUSTED, timeProvider.now()))
    }
}
