package com.moeum.conversation.application.command


import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.kernel.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

// 실행 슬롯 안에서 한 작업을 선점하고 처리한다. DB 작업을 먼저 선점해 대기열에 넣지 않는다.
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

    fun executeNext(): Boolean {
        val now = timeProvider.now()
        val job = responseJobRepository.claimNext(now, policy.leaseExpiresAt(now)) ?: return false
        execute(job)
        return true
    }

    private fun execute(job: ResponseJob) {
        if (policy.isExhausted(job.state.attemptCount)) {
            log.warn("리스 만료로 재시도 횟수를 넘긴 응답 작업을 최종 실패 처리: jobId={}", job.id.value)
            recordFailure(job) { markResponseFailedService.markExhausted(job) }
            return
        }

        try {
            val userMessage = messageRepository.findByUserIdAndId(job.userId, job.userMessageId)
                ?: error("응답 작업의 유저 메시지가 없습니다: messageId=${job.userMessageId.value}")
            val context = messageRepository.findContext(job.userId, job.recordDayId, job.inputSourceRevision, limits.contextMessageLimit)
            val response = conversationResponder.respond(job.userId, context)
            saveGeneratedResponseService.save(job, userMessage, response)
        } catch (_: OptimisticLockingFailureException) {
            log.warn("리스를 잃은 응답 작업이라 결과를 버림(다른 워커가 처리 중): jobId={}", job.id.value)
        } catch (e: Exception) {
            log.error(
                "AI 응답 생성 실패: jobId={}, userId={}, recordDayId={}, attempt={}, error={}",
                job.id.value, job.userId.value, job.recordDayId, job.state.attemptCount, e.message, e,
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
