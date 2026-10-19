package com.moeum.journal.application.command

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.infrastructure.config.GenerationJobProperties
import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MarkGenerationFailedService(
    private val generationJobRepository: GenerationJobRepository,
    generationJobProperties: GenerationJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val policy = generationJobProperties.toPolicy()

    // 재시도가 남았으면 다음 시도를 예약하고, 소진됐으면 최종 실패로 둔다.
    @Transactional
    fun markFailed(job: GenerationJob) {
        generationJobRepository.save(job.failed(policy, timeProvider.now()))
    }

    @Transactional
    fun markExhausted(job: GenerationJob) {
        generationJobRepository.save(job.failedPermanently(timeProvider.now()))
    }
}
