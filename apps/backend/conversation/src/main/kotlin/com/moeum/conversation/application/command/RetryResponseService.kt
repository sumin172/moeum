package com.moeum.conversation.application.command

import com.moeum.conversation.domain.AiQuotaExceededException
import com.moeum.conversation.domain.MessageNotFoundException
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseAlreadyCompletedException
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RetryResponseResult(val message: Message, val responseJob: ResponseJob, val restarted: Boolean)

// 사용자가 실패한 AI 응답을 다시 요청한다. 메시지 재전송(clientMessageId)과 분리된 명시적 요청이라,
// 네트워크 재전송이 의도치 않게 LLM을 다시 부르는 일이 없다.
@Service
class RetryResponseService(
    private val messageRepository: MessageRepository,
    private val responseJobRepository: ResponseJobRepository,
    private val aiQuotaGuard: AiQuotaGuard,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun retry(userId: UserId, messageId: MessageId): RetryResponseResult {
        // 남의 메시지는 user_id 조건으로 아예 조회되지 않는다
        val message = messageRepository.findByUserIdAndId(userId, messageId)
            ?.takeIf { it.role == MessageRole.USER }
            ?: throw MessageNotFoundException("응답을 요청할 수 있는 메시지가 없습니다: messageId=${messageId.value}")
        val job = responseJobRepository.findByUserIdAndUserMessageId(userId, messageId)
            ?: error("응답 작업이 없는 유저 메시지: messageId=${messageId.value}")

        return when (job.state.status) {
            // 이미 대기 중이거나 처리 중이면 같은 요청을 다시 받은 것으로 보고 현재 상태를 돌려준다.
            JobStatus.PENDING, JobStatus.PROCESSING -> RetryResponseResult(message, job, restarted = false)
            JobStatus.COMPLETED -> throw ResponseAlreadyCompletedException("이미 응답이 생성된 메시지입니다: messageId=${messageId.value}")
            JobStatus.FAILED -> {
                // 거부되면 예외로 트랜잭션이 롤백되어 사용량도 늘지 않는다.
                if (!aiQuotaGuard.tryConsume(userId, job.dayDate)) {
                    throw AiQuotaExceededException("오늘 AI 응답 요청 한도를 넘었습니다: userId=${userId.value}")
                }
                val restarted = responseJobRepository.save(job.restarted(timeProvider.now()))
                RetryResponseResult(message, restarted, restarted = true)
            }
        }
    }
}
