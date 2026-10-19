package com.moeum.journal.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.kernel.UuidV7
import com.moeum.platform.job.JobState
import java.time.Instant

object TestGenerationJobs {
    fun pending(userId: UserId, recordDayId: RecordDayId, dayEnd: Instant, now: Instant) =
        GenerationJob(GenerationJobId(UuidV7.generate()), userId, recordDayId, JobState.pending(dayEnd), now, now)
}
