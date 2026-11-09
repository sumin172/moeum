package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.TimeProvider
import com.moeum.platform.job.JobFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// 최종 실패 시 이 실행 주기 이후의 접수를 보고 재개를 정하므로 접수와 같은 기록 잠금 아래에서 전이한다.
@Service
class MarkResponseFailedService(
    private val responseJobRepository: ResponseJobRepository,
    private val records: com.moeum.conversation.domain.RecordDayRepository,
    responseJobProperties: ResponseJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val policy = responseJobProperties.toPolicy()

    // 재시도가 남았으면 다음 시도를 예약하고, 소진됐으면 최종 실패로 둔다.
    @Transactional
    fun markFailed(job: ResponseJob, failure: JobFailure) {
        val record = records.find(job.userId, job.recordDayId, lock = true) ?: error("응답 기록이 없습니다")
        responseJobRepository.save(job.failed(policy, timeProvider.now(), failure, record.sourceRevision))
    }

    // 리스가 만료된 작업을 정리한다. 버전이 올라가므로 중단됐던 워커의 늦은 저장은 거부된다.
    @Transactional
    fun failExpired(now: Instant, limit: Int): List<ResponseJob> =
        responseJobRepository.lockExpired(policy.leaseExpiredBefore(now), limit).map { job ->
            val record = records.find(job.userId, job.recordDayId, lock = true) ?: error("응답 기록이 없습니다")
            responseJobRepository.save(job.leaseExpired(policy, now, record.sourceRevision))
        }
}
