package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.application.command.SaveGeneratedJournalService
import com.moeum.journal.domain.GeneratedJournal
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 생성된 일기 저장(일기 + 리비전 + 작업 완료)을 실제 DB로 검증한다. 리비전은 append-only라 persist로 바로 INSERT하므로,
 * journals를 참조하는 외래 키 순서가 실제 flush에서 맞는지가 핵심이다.
 */
class JournalPersistenceIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var generationJobRepository: GenerationJobRepository

    @Autowired
    lateinit var saveGeneratedJournalService: SaveGeneratedJournalService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `생성된 일기를 리비전·작업 완료와 함께 한 트랜잭션으로 저장한다`() {
        val userId = UserId.generate()
        // 실시간 스케줄러가 건드리지 않도록 먼 미래가 하루 끝인 작업을 만들고, 그 시각으로 선점한다
        val farFuture = Instant.now().plus(Duration.ofDays(400))
        generationJobRepository.save(GenerationJob.pending(userId, LocalDate.of(2027, 2, 1), farFuture, Instant.now()))
        val claimAt = farFuture.plusSeconds(1)
        val job = generateSequence { generationJobRepository.claimNext(claimAt, claimAt.plus(Duration.ofMinutes(5))) }
            .first { it.userId == userId }

        val journal = saveGeneratedJournalService.save(
            job,
            GeneratedJournal(UUID.randomUUID(), "제목", "본문", "m", "p", "v", 10, 5),
            sourceLastMessageId = null,
        )

        val revisionOwner = jdbcTemplate.queryForObject(
            "SELECT user_id FROM journal.journal_revisions WHERE journal_id = ?", UUID::class.java, journal.id.value,
        )
        assertThat(revisionOwner).isEqualTo(userId.value)
        val jobStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM journal.generation_jobs WHERE id = ?", String::class.java, job.id.value,
        )
        assertThat(jobStatus).isEqualTo(JobStatus.COMPLETED.name)
        // 본문은 JSONB {"body"} 구조로 저장된다
        val content = jdbcTemplate.queryForObject(
            "SELECT content->>'body' FROM journal.journals WHERE id = ?", String::class.java, journal.id.value,
        )
        assertThat(content).isEqualTo("본문")
    }
}
