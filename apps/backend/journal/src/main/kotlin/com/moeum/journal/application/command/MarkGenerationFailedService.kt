package com.moeum.journal.application.command

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.infrastructure.config.GenerationJobProperties
import com.moeum.kernel.TimeProvider
import com.moeum.platform.job.JobFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class MarkGenerationFailedService(
    private val generationJobRepository: GenerationJobRepository,
    generationJobProperties: GenerationJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val policy = generationJobProperties.toPolicy()

    // 재시도가 남았으면 다음 시도를 예약하고, 소진됐으면 최종 실패로 둔다.
    @Transactional
    fun markFailed(job: GenerationJob, failure: JobFailure) {
        generationJobRepository.save(job.failed(policy, timeProvider.now(), failure))
    }

    // 리스가 만료된 PROCESSING 작업을 자동 재시도 없이 FAILED로 정리한다. 사용자 재요청으로만 다시 실행한다.
    // 저장하면 version이 올라 중단됐다 되살아난 워커의 늦은 저장은 거부된다.
    @Transactional
    fun failExpired(now: Instant, limit: Int): List<GenerationJob> =
        generationJobRepository.lockExpired(policy.leaseExpiredBefore(now), limit)
            .map { generationJobRepository.save(it.failedPermanently(now)) }
}
