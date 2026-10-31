package com.moeum.conversation.application.command

import com.moeum.conversation.domain.RecordDayNotFoundException
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.conversation.domain.ResponseJobNotFoundException
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.job.ManualRetryUnavailableException
import com.moeum.platform.job.JobRetentionProperties
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// 사용자가 실패한 AI 응답을 다시 요청한다. 메시지 재전송(clientMessageId)과 분리된 명시적 요청이라,
// 네트워크 재전송이 의도치 않게 LLM을 다시 부르는 일이 없다. 같은 입력 범위의 재시도이므로 시도 횟수를 유지한다.
@Service
class RetryResponseService(
    private val responseJobRepository: ResponseJobRepository,
    private val records: com.moeum.conversation.domain.RecordDayRepository,
    private val timeProvider: TimeProvider,
    properties: ResponseJobProperties,
    private val retention: JobRetentionProperties,
) {
    private val policy = properties.toPolicy()
    @Transactional
    fun retry(userId: UserId, recordDayId: RecordDayId): ResponseJob {
        // 남의 기록은 user_id 조건으로 아예 조회되지 않는다
        records.find(userId, recordDayId, lock=true) ?: throw RecordDayNotFoundException("기록이 없습니다")
        val job = responseJobRepository.findByRecord(userId, recordDayId)
            ?: throw ResponseJobNotFoundException("기록에 응답 작업이 없습니다")

        return when (job.state.status) {
            // 이미 대기 중이거나 처리 중이면 같은 요청을 다시 받은 것으로 보고 현재 상태를 돌려준다.
            JobStatus.PENDING, JobStatus.PROCESSING -> job
            JobStatus.COMPLETED -> throw ResponseAlreadyCompletedException("재시도할 실패 응답이 없습니다: recordDayId=${recordDayId.value}")
            JobStatus.FAILED -> {
                val now = timeProvider.now()
                if (!now.isBefore(job.updatedAt.plus(retention.terminalAge))) throw ResponseJobNotFoundException("응답 작업의 재시도 기간이 끝났습니다")
                if (policy.manualRetryExhausted(job.state.attemptCount)) throw ManualRetryUnavailableException(null)
                // 실패 시 저장한 재개 가능 시각(backoff·수동 대기·Retry-After)을 새 메시지에 의한 재개와 같이 지킨다.
                if (now.isBefore(job.state.nextAttemptAt)) throw ManualRetryUnavailableException(job.state.nextAttemptAt)
                responseJobRepository.save(job.restarted(now))
            }
        }
    }
}
