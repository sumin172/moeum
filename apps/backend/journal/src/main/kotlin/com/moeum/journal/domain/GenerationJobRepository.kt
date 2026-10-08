package com.moeum.journal.domain

import com.moeum.journal.domain.model.GenerationJob
import java.time.Instant

interface GenerationJobRepository {
    // 낙관적 락(version)으로 저장한다 — 리스를 뺏긴 뒤의 늦은 저장은 ObjectOptimisticLockingFailureException.
    fun save(job: GenerationJob): GenerationJob
    // 실행할 차례인 작업 하나를 선점한다(SKIP LOCKED). 없으면 null.
    fun claimNext(now: Instant, leaseExpiresAt: Instant): GenerationJob?
}
