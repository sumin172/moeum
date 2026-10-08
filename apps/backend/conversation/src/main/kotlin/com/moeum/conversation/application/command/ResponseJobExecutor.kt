package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.model.ResponseJobId
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

// AI 응답 생성 작업을 실행한다. 진입점은 두 가지다:
// - executeAsync: 메시지 저장·사용자 재시도 직후 바로 한 번 실행 시도 (지연 최소화)
// - claimNext + executeClaimedAsync: poller가 재시도 차례이거나 리스가 만료된 작업을 회수
// LLM 호출은 트랜잭션 밖에서 하고, 결과 저장/실패 기록만 각각 별도 트랜잭션으로 한다.
@Service
class ResponseJobExecutor(
    private val responseJobRepository: ResponseJobRepository,
    private val messageRepository: MessageRepository,
    private val conversationResponder: ConversationResponder,
    private val saveGeneratedResponseService: SaveGeneratedResponseService,
    private val markResponseFailedService: MarkResponseFailedService,
    responseJobProperties: ResponseJobProperties,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(ResponseJobExecutor::class.java)
    private val policy = responseJobProperties.toPolicy()

    @Async
    fun executeAsync(jobId: ResponseJobId) {
        val now = timeProvider.now()
        val job = responseJobRepository.claim(jobId, now, policy.leaseExpiresAt(now))
        if (job == null) {
            log.debug("이미 다른 워커가 잡았거나 실행할 차례가 아닌 응답 작업, 스킵: jobId={}", jobId.value)
            return
        }
        execute(job)
    }

    fun claimNext(): ResponseJob? {
        val now = timeProvider.now()
        return responseJobRepository.claimNext(now, policy.leaseExpiresAt(now))
    }

    @Async
    fun executeClaimedAsync(job: ResponseJob) = execute(job)

    private fun execute(job: ResponseJob) {
        if (policy.isExhausted(job.state.attemptCount)) {
            log.warn("리스 만료로 재시도 횟수를 넘긴 응답 작업을 최종 실패 처리: jobId={}", job.id.value)
            recordFailure(job) { markResponseFailedService.markExhausted(job) }
            return
        }

        try {
            val userMessage = messageRepository.findById(job.userMessageId)
                ?: error("응답 작업의 유저 메시지가 없습니다: messageId=${job.userMessageId.value}")
            val context = messageRepository.findAllByUserIdAndDayDate(job.userId, job.dayDate)
            val response = conversationResponder.respond(job.userId, context)
            saveGeneratedResponseService.save(job, userMessage, response)
        } catch (_: OptimisticLockingFailureException) {
            log.warn("리스를 잃은 응답 작업이라 결과를 버림(다른 워커가 처리 중): jobId={}", job.id.value)
        } catch (e: Exception) {
            log.error(
                "AI 응답 생성 실패: jobId={}, userId={}, dayDate={}, attempt={}, error={}",
                job.id.value, job.userId.value, job.dayDate, job.state.attemptCount, e.message, e,
            )
            recordFailure(job) { markResponseFailedService.markFailed(job, e) }
        }
    }

    // 실패 기록 자체가 실패해도(리스를 뺏긴 경우 포함) 로그는 남아야 한다.
    private fun recordFailure(job: ResponseJob, record: () -> Unit) {
        runCatching(record).onFailure { e ->
            log.warn("응답 작업 실패 기록을 남기지 못함: jobId={}, error={}", job.id.value, e.message)
        }
    }
}
