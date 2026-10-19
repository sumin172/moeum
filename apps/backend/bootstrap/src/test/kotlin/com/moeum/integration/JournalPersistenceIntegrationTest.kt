package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.integration.support.insertPremiumSubscription
import com.moeum.journal.application.command.EditJournalService
import com.moeum.journal.application.command.SaveGeneratedJournalService
import com.moeum.journal.domain.GeneratedJournal
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import com.moeum.platform.security.jwt.JwtProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 최초 결과와 작업 완료의 원자성·TEXT 본문 저장을 실제 PostgreSQL로 검증한다. */
class JournalPersistenceIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var generationJobRepository: GenerationJobRepository

    @Autowired
    lateinit var saveGeneratedJournalService: SaveGeneratedJournalService

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var journalRepository: JournalRepository
    @Autowired lateinit var edit: EditJournalService
    @Autowired lateinit var jwtProvider: JwtProvider

    private fun generation(body: String = "본문") =
        GeneratedJournal("제목", body)
    private fun claimed(user: UserId): GenerationJob {
        val future = Instant.now().plus(Duration.ofDays(600))
        val pending = generationJobRepository.save(com.moeum.journal.domain.model.TestGenerationJobs.pending(user,RecordDayId.generate(),future,Instant.now()))
        return generateSequence { generationJobRepository.claimNext(future.plusSeconds(1),future.plusSeconds(300)) }.first { it.id==pending.id }
    }

    @Test fun `두 기기의 동시 수정은 한 건만 저장한다`() {
        val user = jwtProvider.parse(issueJwt()).userId
        insertPremiumSubscription(jdbcTemplate,user)
        val job = claimed(user)
        val original = saveGeneratedJournalService.save(job,LocalDate.of(2027,2,1),generation())
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val results = listOf("기기 A","기기 B").map { body -> pool.submit<Boolean> {
                gate.await()
                try { edit.edit(user,job.recordDayId,body,body,original.version);true }
                catch (_: com.moeum.journal.domain.JournalVersionConflictException) { false }
                catch (_: org.springframework.dao.OptimisticLockingFailureException) { false }
            } }
            gate.countDown()
            assertThat(results.count { it.get(15,TimeUnit.SECONDS) }).isEqualTo(1)
        }
        val stored = journalRepository.findByUserIdAndRecordDayId(user,job.recordDayId)!!
        assertThat(stored.version).isEqualTo(1)
        assertThat(stored.body).isIn("기기 A","기기 B")
        assertThat(edit.edit(user,job.recordDayId,stored.title,stored.body,stored.version)).isEqualTo(stored)
    }

    @Test fun `리스가 회수된 생성의 늦은 결과는 새 결과와 사용자 수정본을 덮어쓰지 않는다`() {
        val user = jwtProvider.parse(issueJwt()).userId
        insertPremiumSubscription(jdbcTemplate,user)
        val old = claimed(user)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { pool ->
            val late = pool.submit<Boolean> {
                started.countDown();release.await()
                try { saveGeneratedJournalService.save(old,LocalDate.of(2027,2,1),generation("늦은 AI"));false }
                catch (_: org.springframework.dao.OptimisticLockingFailureException) { true }
            }
            try {
                assertThat(started.await(5,TimeUnit.SECONDS)).isTrue()
                jdbcTemplate.update("UPDATE journal.generation_jobs SET lease_expires_at=? WHERE id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),old.id.value)
                val replacement = generateSequence {
                    val at = Instant.now().plus(Duration.ofDays(601))
                    generationJobRepository.claimNext(at,at.plusSeconds(300))
                }.first { it.id==old.id }
                val fresh = saveGeneratedJournalService.save(replacement,LocalDate.of(2027,2,1),generation("정상 AI"))
                edit.edit(user,old.recordDayId,"사용자 제목","사용자 수정본",fresh.version)
                release.countDown()
                assertThat(late.get(15,TimeUnit.SECONDS)).isTrue()
                val stored = journalRepository.findByUserIdAndRecordDayId(user,old.recordDayId)!!
                assertThat(stored.body).isEqualTo("사용자 수정본")
                assertThat(stored.version).isEqualTo(1)
                assertThat(jdbcTemplate.queryForObject<Int>("SELECT count(*) FROM journal.journals WHERE user_id=?",
                    user.value)).isEqualTo(1)
            } finally { release.countDown() }
        }
    }

    @Test fun `기존 결과가 있는 복구 작업도 사용자가 수정한 현재 결과를 변경하지 않는다`() {
        val user = jwtProvider.parse(issueJwt()).userId
        insertPremiumSubscription(jdbcTemplate,user)
        val initial = claimed(user)
        val original = saveGeneratedJournalService.save(initial,LocalDate.of(2027,2,1),generation())
        val edited = edit.edit(user,initial.recordDayId,"수정 제목","수정 본문",original.version)
        // 결과가 남아 있는 복구 상태를 주입한다. 서비스에 AI 재작성 API를 추가하지 않는다.
        jdbcTemplate.update("UPDATE journal.generation_jobs SET status='PROCESSING',version=version+1 WHERE id=?",initial.id.value)
        val repair = generationJobRepository.findByUserIdAndRecordDayId(user,initial.recordDayId)!!
        val result = saveGeneratedJournalService.save(repair,LocalDate.of(2027,2,1),generation("교체하면 안 되는 결과"))
        assertThat(result).isEqualTo(edited)
        assertThat(journalRepository.findByUserIdAndRecordDayId(user,initial.recordDayId)).isEqualTo(edited)
    }

    @Test fun `표시 날짜가 같아도 다른 기록은 별도 일기로 저장 조회된다`() {
        val user = UserId.generate()
        val date = LocalDate.of(2027,2,1)
        val ids = listOf(RecordDayId.generate(),RecordDayId.generate())
        val future = Instant.now().plus(Duration.ofDays(500))
        val created = ids.map { generationJobRepository.save(com.moeum.journal.domain.model.TestGenerationJobs.pending(user,it,future,Instant.now())).id }
        created.forEachIndexed { index,id ->
            val job = generateSequence { generationJobRepository.claimNext(future.plusSeconds(1),future.plusSeconds(300)) }.first { it.id == id }
            saveGeneratedJournalService.save(job,date,GeneratedJournal("제목$index", "본문$index"))
        }
        assertThat(journalRepository.findByDate(user,date)).hasSize(2)
        assertThat(journalRepository.findByUserIdAndRecordDayId(user,ids[0])!!.body).isEqualTo("본문0")
        assertThat(journalRepository.findByUserIdAndRecordDayId(user,ids[1])!!.body).isEqualTo("본문1")
    }

    @Test
    fun `생성된 일기를 TEXT 본문과 작업 완료로 한 트랜잭션에 저장한다`() {
        val userId = UserId.generate()
        // 실시간 스케줄러가 건드리지 않도록 먼 미래가 하루 끝인 작업을 만들고, 그 시각으로 선점한다
        val farFuture = Instant.now().plus(Duration.ofDays(400))
        generationJobRepository.save(com.moeum.journal.domain.model.TestGenerationJobs.pending(userId, RecordDayId.generate(), farFuture, Instant.now()))
        val claimAt = farFuture.plusSeconds(1)
        val job = generateSequence { generationJobRepository.claimNext(claimAt, claimAt.plus(Duration.ofMinutes(5))) }
            .first { it.userId == userId }

        val journal = saveGeneratedJournalService.save(
            job,
            LocalDate.of(2027, 2, 1),
            GeneratedJournal("제목", "본문"),
        )

        val jobStatus = jdbcTemplate.queryForObject<String>(
            "SELECT status FROM journal.generation_jobs WHERE id = ?", job.id.value,
        )
        assertThat(jobStatus).isEqualTo(JobStatus.COMPLETED.name)
        // JSON wrapper 없이 TEXT 본문을 저장한다
        val content = jdbcTemplate.queryForObject<String>(
            "SELECT body FROM journal.journals WHERE id = ?", journal.id.value,
        )
        assertThat(content).isEqualTo("본문")
        assertThat(jdbcTemplate.queryForObject<String>("SELECT data_type FROM information_schema.columns WHERE table_schema='journal' AND table_name='journals' AND column_name='body'")).isEqualTo("text")
        assertThat(jdbcTemplate.queryForObject<Boolean>("SELECT to_regclass('journal.journal_revisions') IS NULL")).isTrue()
    }
}
