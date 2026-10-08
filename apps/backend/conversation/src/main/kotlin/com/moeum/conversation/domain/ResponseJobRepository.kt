package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.model.ResponseJobId
import java.time.Instant

interface ResponseJobRepository {
    // 낙관적 락(version)으로 저장한다 — 리스를 뺏긴 뒤의 늦은 저장은 ObjectOptimisticLockingFailureException.
    fun save(job: ResponseJob): ResponseJob
    fun findByUserMessageId(userMessageId: MessageId): ResponseJob?
    fun findAllByUserMessageIdIn(userMessageIds: Collection<MessageId>): List<ResponseJob>
    // 실행할 차례인 작업 하나를 선점한다(SKIP LOCKED). 없으면 null.
    fun claimNext(now: Instant, leaseExpiresAt: Instant): ResponseJob?
    // 특정 작업을 선점한다. 이미 다른 워커가 잡았거나 아직 차례가 아니면 null.
    fun claim(id: ResponseJobId, now: Instant, leaseExpiresAt: Instant): ResponseJob?
}
