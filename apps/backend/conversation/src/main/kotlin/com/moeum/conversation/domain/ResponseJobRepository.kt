package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.UserId
import java.time.Instant

interface ResponseJobRepository {
    // 메시지 접수와 같은 트랜잭션에서 새 작업을 INSERT한다. 기존 상태 갱신과 구분한다.
    fun insert(job: ResponseJob)
    // 낙관적 락(version)으로 저장한다 — 리스를 뺏긴 뒤의 늦은 저장은 ObjectOptimisticLockingFailureException.
    fun save(job: ResponseJob): ResponseJob
    fun findByUserIdAndUserMessageId(userId: UserId, userMessageId: MessageId): ResponseJob?
    fun findAllByUserIdAndUserMessageIdIn(userId: UserId, userMessageIds: Collection<MessageId>): List<ResponseJob>
    fun hasLaterStarted(job: ResponseJob): Boolean
    fun supersededIds(userId: UserId, ids: Collection<com.moeum.conversation.domain.model.ResponseJobId>): Set<com.moeum.conversation.domain.model.ResponseJobId>
    fun findAllByUserIdAndIdIn(userId: UserId, ids: Collection<com.moeum.conversation.domain.model.ResponseJobId>): List<ResponseJob>
    // 선점(claim)은 워커가 사용자와 무관하게 실행할 차례인 작업을 고르는 경로라 user_id 없이 조회한다 —
    // 작업 테이블은 사용자 단위 파티셔닝 대상이 아니다(완료된 작업은 정리 대상).
    // 미리 확보한 실행 슬롯 수 이내에서 기록당 한 작업을 선점한다(SKIP LOCKED).
    fun claimBatch(now: Instant, leaseExpiresAt: Instant, limit: Int): List<ResponseJob>
}
