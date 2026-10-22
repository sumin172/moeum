package com.moeum.journal.application.command

import com.moeum.kernel.RecordDayId
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalGenerationNotRetryableException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.job.ManualRetryUnavailableException
import com.moeum.platform.job.JobRetentionProperties
import com.moeum.journal.infrastructure.config.GenerationJobProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 최종 실패한 일기 생성을 누적 시도 상한과 대기 간격 안에서 다시 요청한다. 실패 복구는 요금제와 무관하다.
// 다시 시작한 작업은 Executor의 다음 poll에 실행된다.
@Service
class RetryJournalGenerationService(
    private val generationJobRepository: GenerationJobRepository,
    private val timeProvider: TimeProvider,
    private val journals: com.moeum.journal.domain.JournalRepository,
    properties: GenerationJobProperties,
    private val retention: JobRetentionProperties,
) {
    private val policy = properties.toPolicy()
    @Transactional
    fun retry(userId: UserId, recordDayId: RecordDayId): GenerationJob {
        val job = generationJobRepository.findByUserIdAndRecordDayId(userId, recordDayId)
            ?: run {
                if (journals.findByUserIdAndRecordDayId(userId, recordDayId) != null) {
                    throw JournalGenerationNotRetryableException("이미 생성된 일기입니다")
                }
                throw JournalNotFoundException("그 하루에 생성할 일기가 없습니다: recordDayId=${recordDayId.value}")
            }
        return when (job.state.status) {
            // 이미 대기·진행 중이면 같은 요청을 다시 받은 것으로 보고 현재 상태를 돌려준다
            JobStatus.PENDING, JobStatus.PROCESSING -> job
            JobStatus.COMPLETED -> throw JournalGenerationNotRetryableException("이미 생성된 일기입니다: recordDayId=${recordDayId.value}")
            JobStatus.FAILED -> {
                val now = timeProvider.now()
                if (!now.isBefore(job.updatedAt.plus(retention.terminalAge))) throw JournalNotFoundException("일기 생성 작업의 재시도 기간이 끝났습니다")
                val retryAt = policy.manualRetryAt(job.state.attemptCount, job.updatedAt)
                if (retryAt == null || now.isBefore(retryAt)) throw ManualRetryUnavailableException(retryAt)
                generationJobRepository.save(job.restarted(now))
            }
        }
    }
}
