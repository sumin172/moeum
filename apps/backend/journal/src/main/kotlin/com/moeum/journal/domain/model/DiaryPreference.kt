package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalTime

data class DiaryPreference(
    val userId: UserId,
    val generationTime: LocalTime,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun withGenerationTime(generationTime: LocalTime, now: Instant): DiaryPreference =
        copy(generationTime = generationTime, updatedAt = now)

    companion object {
        // 실사용 패턴 확인 전 잠정값. 최종 확정은 docs/DEVELOPMENT_STAGES.md "구현하면서 결정" 참고.
        val DEFAULT_GENERATION_TIME: LocalTime = LocalTime.of(2, 0)
    }
}
