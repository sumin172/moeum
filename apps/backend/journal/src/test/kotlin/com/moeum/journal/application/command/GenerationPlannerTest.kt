package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.ActiveDay
import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.journal.support.InMemoryGenerationJobRepository
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class GenerationPlannerTest {

    private class FakeActivityQuery(var activeDays: List<ActiveDay>) : ConversationActivityQuery {
        override fun findActiveDays(from: Instant, to: Instant): List<ActiveDay> = activeDays
        override fun findMessages(userId: UserId, dayDate: LocalDate): List<MessageSnapshot> = error("not used in this test")
    }

    private val now = Instant.parse("2026-08-02T10:00:00Z")
    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = now
    }
    private val userId = UserId.generate()

    @Test
    fun `새 활동이 생긴 하루마다 그 하루가 끝나는 시각에 실행될 PENDING Job을 만든다`() {
        val dayEnd = Instant.parse("2026-08-02T17:00:00Z")
        val jobRepository = InMemoryGenerationJobRepository()
        val planner = GenerationPlanner(
            FakeActivityQuery(listOf(ActiveDay(userId, LocalDate.of(2026, 8, 2), dayEnd))),
            jobRepository,
            timeProvider,
        )

        planner.plan(now.minusSeconds(600), now, "RECENT")

        val job = jobRepository.jobs.values.single()
        assertThat(job.diaryDate).isEqualTo(LocalDate.of(2026, 8, 2))
        assertThat(job.state.nextAttemptAt).isEqualTo(dayEnd)
        assertThat(job.state.status).isEqualTo(JobStatus.PENDING)
    }

    @Test
    fun `같은 하루를 다시 계획하면 기존 Job을 그대로 두고 새로 만들지 않는다`() {
        val day = ActiveDay(userId, LocalDate.of(2026, 8, 2), Instant.parse("2026-08-02T17:00:00Z"))
        val jobRepository = InMemoryGenerationJobRepository()
        val planner = GenerationPlanner(FakeActivityQuery(listOf(day)), jobRepository, timeProvider)

        planner.plan(now.minusSeconds(600), now, "RECENT")
        planner.plan(now.minusSeconds(3 * 86400), now, "RECONCILIATION")

        assertThat(jobRepository.jobs).hasSize(1)
    }
}
