package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.*
import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.journal.domain.GeneratedJournal
import com.moeum.journal.domain.JournalGenerator
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.infrastructure.config.GenerationJobProperties
import com.moeum.journal.support.InMemoryGenerationJobRepository
import com.moeum.journal.support.InMemoryJournalRepository
import com.moeum.kernel.UuidV7
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.llm.LlmException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class GenerationExecutorTest {

    private val userId = UserId.generate()
    private val lastUserMessageId = UuidV7.generate()
    private val recordDayId = RecordDayId.generate()
    private val displayDate = LocalDate.of(2026, 8, 2)
    private val dayEnd = Instant.parse("2026-08-02T17:00:00Z")
    private var now = dayEnd.plusSeconds(30)
    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = now
    }
    private val jobRepository = InMemoryGenerationJobRepository()
    private val journalRepository = InMemoryJournalRepository()
    private val journals get() = journalRepository.journals.values.toList()
    private val activityQuery = object : RecordDayOperations {
        override fun closeNext() = false
        override fun claimDelivery(): RecordDelivery? = null
        override fun acknowledge(delivery: RecordDelivery) = true
        override fun freezeInput(userId: UserId, recordDayId: RecordDayId) =
            FrozenRecordInput(1, listOf(MessageSnapshot(lastUserMessageId, "USER", "오늘 산책을 했다", dayEnd.minusSeconds(3600))))
    }

    private class ScriptedGenerator(private val failures: Int) : JournalGenerator {
        var calls = 0
        override fun generate(displayDate: LocalDate, messages: List<MessageSnapshot>): GeneratedJournal {
            calls++
            if (calls <= failures) throw LlmException("생성 실패")
            return GeneratedJournal(UUID.randomUUID(), "산책", "산책을 했다", "gemini", "v1")
        }
    }

    private val properties = GenerationJobProperties(maxAttempts = 2, backoff = listOf(Duration.ofMinutes(1)))

    private fun executor(generator: JournalGenerator) = GenerationExecutor(
        generationJobRepository = jobRepository,
        recordDayOperations = activityQuery,
        journalGenerator = generator,
        saveGeneratedJournalService = SaveGeneratedJournalService(journalRepository, jobRepository, timeProvider),
        markGenerationFailedService = MarkGenerationFailedService(jobRepository, properties, timeProvider),
        generationJobProperties = properties,
        timeProvider = timeProvider,
    )

    private fun enqueue(): GenerationJob = jobRepository.save(GenerationJob.pending(userId, recordDayId, displayDate, dayEnd, dayEnd.minusSeconds(7200)))

    @Test
    fun `하루가 끝난 작업을 실행해 일기를 즉시 보관하고 작업을 완료한다`() {
        val job = enqueue()

        executor(ScriptedGenerator(failures = 0)).executeNext()

        val completed = jobRepository.jobs.getValue(job.id)
        assertThat(completed.state.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(completed.journalId).isEqualTo(journals.single().id)
        assertThat(journals.single().displayDate).isEqualTo(displayDate)
        // 입력 동결 시의 원본 버전을 결과에 남긴다
        assertThat(journals.single().sourceRevision).isEqualTo(1)
        // 사용자 소유 데이터는 user_id를 직접 갖는다
        assertThat(journals.single().userId).isEqualTo(userId)
    }

    @Test
    fun `아직 끝나지 않은 하루의 작업은 실행하지 않는다`() {
        val job = enqueue()
        now = dayEnd.minusSeconds(1)
        val generator = ScriptedGenerator(failures = 0)

        executor(generator).executeNext()

        assertThat(generator.calls).isZero()
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.PENDING)
    }

    @Test
    fun `실패하면 backoff 뒤에 다시 시도하고, 재시도를 모두 실패하면 최종 실패로 끝난다`() {
        val job = enqueue()
        val generator = ScriptedGenerator(failures = 2)
        val executor = executor(generator)

        executor.executeNext()
        val retrying = jobRepository.jobs.getValue(job.id)
        assertThat(retrying.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(retrying.state.lastErrorCode).isEqualTo("LlmException")

        // backoff 전에는 다시 실행하지 않는다
        executor.executeNext()
        assertThat(generator.calls).isEqualTo(1)

        now = now.plus(Duration.ofMinutes(2))
        executor.executeNext()

        assertThat(generator.calls).isEqualTo(2)
        assertThat(jobRepository.jobs.getValue(job.id).state.status).isEqualTo(JobStatus.FAILED)
        assertThat(journals).isEmpty()
    }
}
