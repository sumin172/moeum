package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant

interface ResponseJobRepository {
    // 기록의 첫 접수 트랜잭션에서 INSERT한다. 기존 상태 갱신과 구분한다.
    fun insert(job: ResponseJob)
    // 낙관적 락(version)으로 저장한다 — 리스를 뺏긴 뒤의 늦은 저장은 ObjectOptimisticLockingFailureException.
    fun save(job: ResponseJob): ResponseJob
    fun findByRecord(userId: UserId, recordDayId: RecordDayId): ResponseJob?
    // 선점은 사용자와 무관하게 실행할 차례인 기록을 고르는 경로라 user_id 없이 조회한다.
    // 미리 확보한 실행 슬롯 수 이내에서 기록 행을 잠근 뒤 최신 원본 순번으로 실행 주기의 관측 상한을 정한다(SKIP LOCKED).
    fun claimBatch(now: Instant, leaseExpiresAt: Instant, limit: Int): List<ResponseJob>
}
