package com.moeum.journal.domain

import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.GenerationJobId
import com.moeum.journal.domain.model.GenerationJobStatus
import java.time.Instant

interface GenerationJobRepository {
    fun save(job: GenerationJob): GenerationJob
    fun findPendingDue(now: Instant): List<GenerationJob>
    // Executor의 원자적 claim(PENDING → PROCESSING). 동시에 호출돼도 정확히 하나만 성공한다.
    fun compareAndSetStatus(id: GenerationJobId, expected: GenerationJobStatus, updated: GenerationJobStatus): Boolean
}
