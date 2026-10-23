package com.moeum.journal.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobPolicy
import com.moeum.platform.job.JobFailure
import com.moeum.platform.job.JobState
import java.time.Instant

// 마감된 기록당 하나의 최초 일기 생성 작업. recordDayId가 멱등키다.
data class GenerationJob(
    val id: GenerationJobId,
    val userId: UserId,
    val recordDayId: RecordDayId,
    val state: JobState,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun completed(now: Instant): GenerationJob =
        copy(
            state = state.completed(),
            updatedAt = now,
        )

    fun failed(policy: JobPolicy, now: Instant, failure: JobFailure): GenerationJob =
        copy(state = state.failed(policy, now, failure), updatedAt = now)

    // 오류 또는 자동 시도 상한으로 종료한 작업을 사용자가 다시 요청한 경우
    fun restarted(now: Instant): GenerationJob = copy(state = state.restarted(now), updatedAt = now)

}
