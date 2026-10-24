package com.moeum.conversation.application.command


import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.TimeProvider
import com.moeum.platform.observability.SafeFailure
import com.moeum.platform.observability.WorkTrace
import com.moeum.platform.job.JobFailure
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant

// 예약된 실행 슬롯 이내에서 배치 선점하고, 각 작업을 대기열 없이 처리한다.
// LLM 호출은 트랜잭션 밖에서 하고, 결과 저장/실패 기록만 각각 별도 트랜잭션으로 한다.
@Service
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
class ResponseJobExecutor(
    private val responseJobRepository: ResponseJobRepository,
    private val messageRepository: MessageRepository,
    private val conversationResponder: ConversationResponder,
    private val saveGeneratedResponseService: SaveGeneratedResponseService,
    private val markResponseFailedService: MarkResponseFailedService,
    responseJobProperties: ResponseJobProperties,
    private val timeProvider: TimeProvider,
    private val limits: com.moeum.conversation.infrastructure.config.ConversationLimitProperties,
) {
    private val log = LoggerFactory.getLogger(ResponseJobExecutor::class.java)
    private val policy = responseJobProperties.toPolicy()
    val claimBatchSize = responseJobProperties.claimBatchSize
    // 비동기 선점 완료 시각 때문에 다음 정규 poll까지 건너뛰지 않도록 반 간격만 쉰다.
    private val idleProbeDelay = responseJobProperties.pollInterval.dividedBy(2)
    private val nextProbeAt = AtomicReference(Instant.MIN)

    fun canPoll(): Boolean = !timeProvider.now().isBefore(nextProbeAt.get())

    fun claim(reservedSlots: Int): List<ResponseJob> {
        val now=timeProvider.now()
        if(now.isBefore(nextProbeAt.get()))return emptyList()
        return responseJobRepository.claimBatch(now,policy.leaseExpiresAt(now),reservedSlots).also {
            if(it.isEmpty())nextProbeAt.set(now.plus(idleProbeDelay))
        }
    }

    fun execute(job: ResponseJob) = WorkTrace.run(job.id.value, job.recordDayId.value, job.state.attemptCount) { process(job) }

    private fun process(job: ResponseJob) {
        if (policy.isExhausted(job.state.attemptCount)) {
            log.warn("리스 만료로 재시도 횟수를 넘긴 응답 작업을 최종 실패 처리: jobId={}", job.id.value)
            recordFailure(job) { markResponseFailedService.markExhausted(job) }
            return
        }

        try {
            val userMessage = messageRepository.findByUserIdAndId(job.userId, job.userMessageId)
                ?: error("응답 작업의 유저 메시지가 없습니다: messageId=${job.userMessageId.value}")
            val context = messageRepository.findContext(job.userId, job.recordDayId, job.inputSourceRevision, limits.contextMessageLimit)
            val response = conversationResponder.respond(context)
            saveGeneratedResponseService.save(job, userMessage, response)
            log.atInfo().addKeyValue("event", "job.completed").log("Worker job completed")
        } catch (_: OptimisticLockingFailureException) {
            log.warn("리스를 잃은 응답 작업이라 결과를 버림(다른 워커가 처리 중): jobId={}", job.id.value)
        } catch (e: Exception) {
            val failure = JobFailure.classify(e)
            log.atError().addKeyValue("event", "job.failed")
                .addKeyValue("errorCode", SafeFailure.code(e))
                .addKeyValue("retryKind", failure.kind.name)
                .addKeyValue("location", SafeFailure.location(e))
                .log("Worker job failed")
            recordFailure(job) { markResponseFailedService.markFailed(job, failure) }
        }
    }

    // 실패 기록 자체가 실패해도(리스를 뺏긴 경우 포함) 로그는 남아야 한다.
    private fun recordFailure(job: ResponseJob, record: () -> Unit) {
        runCatching(record).onFailure { e ->
            log.warn("응답 작업 실패 기록을 남기지 못함: jobId={}, error={}", job.id.value, SafeFailure.code(e))
        }
    }
}
