package com.moeum.journal.application.command

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.infrastructure.config.GenerationJobProperties
import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED"

@Service
class MarkGenerationFailedService(
    private val generationJobRepository: GenerationJobRepository,
    generationJobProperties: GenerationJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val policy = generationJobProperties.toPolicy()

    // 재시도가 남았으면 다음 시도를 예약하고, 소진됐으면 최종 실패로 둔다.
    @Transactional
    fun markFailed(job: GenerationJob, cause: Throwable) {
        generationJobRepository.save(job.failed(cause::class.simpleName ?: "UNKNOWN", policy, timeProvider.now()))
    }

    @Transactional
    fun markExhausted(job: GenerationJob) {
        generationJobRepository.save(job.failedPermanently(ATTEMPTS_EXHAUSTED, timeProvider.now()))
    }
}
