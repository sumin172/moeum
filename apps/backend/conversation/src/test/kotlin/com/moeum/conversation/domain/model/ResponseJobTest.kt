package com.moeum.conversation.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobState
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ResponseJobTest {

    private val now = Instant.parse("2026-08-08T10:00:00Z")

    private fun job(status: JobStatus, attemptCount: Int) = ResponseJob(
        recordDayId = RecordDayId.generate(),
        userId = UserId.generate(),
        state = JobState(status = status, attemptCount = attemptCount, nextAttemptAt = now, version = 1),
        observedSourceRevision = 1,
        createdAt = now,
        updatedAt = now,
    )

    private fun mergedAtIntake(previous: ResponseJob): Boolean =
        previous.inputAccepted(now.plusSeconds(1))?.mergedInputFrom(previous) ?: false

    @Test
    fun `완료 뒤 새 입력으로 열린 실행 주기는 합침으로 보지 않는다`() {
        assertThat(mergedAtIntake(job(JobStatus.COMPLETED, attemptCount = 1))).isFalse()
    }

    @Test
    fun `재시도 대기·최종 실패 뒤 새 입력으로 열린 실행 주기는 합침으로 본다`() {
        assertThat(mergedAtIntake(job(JobStatus.PENDING, attemptCount = 1))).isTrue()
        assertThat(mergedAtIntake(job(JobStatus.FAILED, attemptCount = 3))).isTrue()
    }

    @Test
    fun `첫 실행 대기·실행 중 접수는 새 실행 주기를 열지 않는다`() {
        assertThat(mergedAtIntake(job(JobStatus.PENDING, attemptCount = 0))).isFalse()
        assertThat(mergedAtIntake(job(JobStatus.PROCESSING, attemptCount = 1))).isFalse()
    }
}
