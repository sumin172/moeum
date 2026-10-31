package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaveGeneratedResponseService(
    private val messageRepository: MessageRepository,
    private val responseJobRepository: ResponseJobRepository,
    private val timeProvider: TimeProvider,
    private val records: com.moeum.conversation.domain.RecordDayRepository,
) {
    // 작업 전이를 먼저 저장한다 — 리스를 뺏긴 워커면 여기서 낙관적 락 충돌로 트랜잭션 전체가 롤백되어
    // assistant 메시지가 중복 저장되지 않는다. 기록 잠금 아래의 원본 순번으로 이어서 답할 입력을 판단한다.
    @Transactional
    fun save(job: ResponseJob, answeredThrough: Long, response: ConversationResponse) {
        val record = records.find(job.userId, job.recordDayId, lock=true) ?: error("응답 기록이 없습니다")
        val now = timeProvider.now()
        responseJobRepository.save(job.succeeded(answeredThrough, record.sourceRevision, now))
        messageRepository.append(
            Message.assistantMessage(
                id = MessageId.generate(),
                userId = job.userId,
                content = response.content,
                occurredAt = now,
                recordDayId = job.recordDayId,
                sourceSequence = answeredThrough,
            ),
        )
    }
}
