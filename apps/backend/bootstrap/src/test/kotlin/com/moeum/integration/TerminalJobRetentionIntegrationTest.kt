package com.moeum.integration

import com.moeum.journal.domain.model.failedPermanently

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.publicapi.RecordDelivery
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.infrastructure.scheduling.TerminalResponseJobCleaner
import com.moeum.journal.application.command.RegisterRecordGenerationService
import com.moeum.journal.application.command.RetryJournalGenerationService
import com.moeum.journal.domain.*
import com.moeum.journal.domain.model.*
import com.moeum.journal.infrastructure.scheduling.TerminalGenerationJobCleaner
import com.moeum.kernel.*
import com.moeum.platform.job.JobRetentionProperties
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.*
import java.util.UUID
import java.util.concurrent.*

class TerminalJobRetentionIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var responses: ResponseJobRepository
    @Autowired lateinit var retryResponse: RetryResponseService
    @Autowired lateinit var generations: GenerationJobRepository
    @Autowired lateinit var journals: JournalRepository
    @Autowired lateinit var registration: RegisterRecordGenerationService
    @Autowired lateinit var retry: RetryJournalGenerationService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager

    private val users = mutableListOf<UserId>()
    private val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    private val old = now.minus(Duration.ofDays(40))
    private val time = object : TimeProvider { override fun now() = now }
    private val props = JobRetentionProperties(batchSize=2)
    private fun user() = UserId.generate().also(users::add)
    private fun input(user: UserId, close: Boolean = true): SaveMessageResult {
        val saved = save.save(user, SaveMessageCommand(UUID.randomUUID(), "원본",
            now.minus(Duration.ofDays(1)), "Asia/Seoul"))
        awaitResponded(saved)
        if (close) {
            val boundary = Timestamp.from(Instant.now())
            jdbc.update("UPDATE conversation.record_days SET close_at=?,closed_at=?,delivery_acknowledged=true WHERE id=?",
                boundary, boundary, saved.record.id.value)
        }
        return saved
    }
    // 공유 DB의 대화 워커가 이 작업을 실행하는 동안에는 기록 잠금 때문에 정리 SQL이 행을 건너뛴다(SKIP LOCKED).
    // 워커가 응답을 끝낸 뒤에 시험이 원하는 상태를 SQL로 지정한다.
    private fun awaitResponded(saved: SaveMessageResult) {
        repeat(200) {
            if (jdbc.queryForObject<String>("SELECT status FROM conversation.response_jobs WHERE record_day_id=?",
                    saved.record.id.value) == "COMPLETED") return
            Thread.sleep(50)
        }
        error("시험 준비 중 응답 작업이 완료되지 않았습니다")
    }
    private fun response(saved: SaveMessageResult, complete: Boolean = true): ResponseJob {
        val pending = saved.responseJob!!
        if (complete) jdbc.update("UPDATE conversation.response_jobs SET status='COMPLETED',updated_at=?,version=version+1 WHERE record_day_id=?",
            Timestamp.from(old), pending.recordDayId.value)
        // 대기 상태는 워커가 선점하지 않도록 먼 미래로 예약한다
        else jdbc.update("UPDATE conversation.response_jobs SET status='PENDING',next_attempt_at=?,version=version+1 WHERE record_day_id=?",
            Timestamp.from(now.plus(Duration.ofDays(10))), pending.recordDayId.value)
        return responses.findByRecord(pending.userId, pending.recordDayId)!!
    }
    private fun responseCleaner() = TerminalResponseJobCleaner(jdbc, time, props)
    private fun generationCleaner() = TerminalGenerationJobCleaner(jdbc, time, props)

    @AfterEach fun cleanupFixture() {
        users.forEach {
            jdbc.update("DELETE FROM conversation.response_jobs WHERE user_id=?", it.value)
            jdbc.update("DELETE FROM journal.generation_jobs WHERE user_id=?", it.value)
            jdbc.update("DELETE FROM journal.journals WHERE user_id=?", it.value)
        }
    }

    @Test fun `마감된 기록의 완료 작업만 작은 배치로 지우고 원본 중복 접수는 보존한다`() {
        val saved = (1..3).map { input(user()) }
        val ids = saved.map { response(it).recordDayId.value }
        assertThat(responseCleaner().cleanBatch()).isEqualTo(2)
        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM conversation.response_jobs WHERE record_day_id IN (?,?,?)",
            *ids.toTypedArray())).isEqualTo(1)
        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
        saved.forEach {
            assertThat(jdbc.queryForObject<String>("SELECT content FROM conversation.messages WHERE id=?", it.message.id.value)).isEqualTo("원본")
            val duplicate = save.save(it.message.userId, SaveMessageCommand(it.message.clientMessageId!!, it.message.content,
                it.message.occurredAt, "Asia/Seoul"))
            assertThat(duplicate.message.id).isEqualTo(it.message.id)
            assertThat(duplicate.responseJob).isNull()
        }
    }

    @Test fun `정기 정리는 한 배치보다 큰 완료 작업 backlog를 이어서 처리한다`() {
        val responseIds = (1..5).map { response(input(user())).recordDayId.value }
        val generationIds = (1..5).map {
            val owner = user()
            generations.save(TestGenerationJobs.pending(owner, RecordDayId.generate(), now, old).completed(old)).id.value
        }

        responseCleaner().scheduledCleanup()
        generationCleaner().scheduledCleanup()

        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM conversation.response_jobs WHERE record_day_id IN (?,?,?,?,?)",
            *responseIds.toTypedArray())).isZero()
        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE id IN (?,?,?,?,?)",
            *generationIds.toTypedArray())).isZero()
    }

    @Test fun `열린 기록·최근 종료·대기·실행 중 작업은 보존하고 마감된 기록의 실패는 기한 안에 수동 재시도할 수 있다`() {
        val open = input(user(), close = false)
        val openJob = response(open)
        val recent = input(user())
        jdbc.update("UPDATE conversation.response_jobs SET status='COMPLETED',updated_at=? WHERE record_day_id=?",
            Timestamp.from(now), recent.record.id.value)
        val pendingJob = response(input(user()), complete=false)
        val processingJob = response(input(user()), complete=false)
        jdbc.update("UPDATE conversation.response_jobs SET status='PROCESSING',lease_expires_at=? WHERE record_day_id=?",
            Timestamp.from(now.plus(Duration.ofDays(10))), processingJob.recordDayId.value)
        val failed = input(user())
        jdbc.update("UPDATE conversation.response_jobs SET status='FAILED',attempt_count=3,updated_at=? WHERE record_day_id=?",
            Timestamp.from(now), failed.record.id.value)
        val expired = input(user())
        response(expired)

        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
        listOf(openJob.recordDayId, recent.record.id, pendingJob.recordDayId, processingJob.recordDayId, failed.record.id).forEach {
            assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM conversation.response_jobs WHERE record_day_id=?", it.value)).isEqualTo(1)
        }
        assertThat(responses.findByRecord(expired.message.userId, expired.record.id)).isNull()
        // 마감 뒤 새 메시지는 다음 기록에 속하므로 이전 기록의 실패는 수동 재시도로만 복구한다
        assertThat(retryResponse.retry(failed.message.userId, failed.record.id).state.status)
            .isEqualTo(com.moeum.platform.job.JobStatus.PENDING)
    }

    @Test fun `정리 작업은 잠긴 기록을 건너뛰고 잠금 해제 이후 정리한다`() {
        val saved = input(user())
        response(saved)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { pool ->
            val lock = pool.submit {
                TransactionTemplate(transactions).executeWithoutResult {
                    jdbc.queryForObject<UUID>("SELECT id FROM conversation.record_days WHERE id=? FOR UPDATE", saved.record.id.value)
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                assertThat(responseCleaner().cleanBatch()).isZero()
            } finally { release.countDown(); lock.get(10, TimeUnit.SECONDS) }
        }
        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
    }

    @Test fun `일기 작업 정리 뒤에도 생성 원본 정보 수정본 재전달 멱등성과 완료 재시도 거절은 유지한다`() {
        val user = user()
        val day = RecordDayId.generate()
        val date = LocalDate.now()
        val original = journals.insertGenerated(Journal(JournalId.generate(), user, day, date, "제목", "본문", 0))
        val edited = journals.updateEdited(original.copy(body="사용자 수정본"))
        val pending = TestGenerationJobs.pending(user, day, now.plus(Duration.ofDays(10)), old)
        generations.save(pending.completed(old))
        val failed = TestGenerationJobs.pending(user, RecordDayId.generate(), now.plus(Duration.ofDays(10)), old)
            .failedPermanently(now)
        generations.save(failed)
        val processing = generations.save(TestGenerationJobs.pending(user, RecordDayId.generate(), now.plus(Duration.ofDays(10)), old))
        jdbc.update("UPDATE journal.generation_jobs SET status='PROCESSING',lease_expires_at=? WHERE id=?",
            Timestamp.from(now.plus(Duration.ofDays(10))), processing.id.value)
        val recent = generations.save(TestGenerationJobs.pending(user, RecordDayId.generate(), now.plus(Duration.ofDays(10)), now))
        assertThat(generationCleaner().cleanBatch()).isEqualTo(2)
        assertThat(journals.findByUserIdAndRecordDayId(user, day)).isEqualTo(edited)
        assertThat(generations.findByUserIdAndRecordDayId(user, day)).isNull()
        assertThat(registration.register(RecordDelivery(day, user, old, 1))).isFalse()
        assertThatThrownBy { retry.retry(user, day) }.isInstanceOf(JournalGenerationNotRetryableException::class.java)
        assertThat(generations.findByUserIdAndRecordDayId(user, failed.recordDayId)).isNull()
        listOf(processing, recent).forEach { assertThat(generations.findByUserIdAndRecordDayId(user, it.recordDayId)).isNotNull() }
    }

    @Test fun `30일 지난 실패 작업은 원본을 보존하고 정리한다`() {
        val saved = input(user())
        jdbc.update("UPDATE conversation.response_jobs SET status='FAILED',updated_at=? WHERE record_day_id=?",
            Timestamp.from(old), saved.record.id.value)
        val owner = user()
        val generation = generations.save(TestGenerationJobs.pending(owner, RecordDayId.generate(), now, old).failedPermanently(old))

        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
        assertThat(generationCleaner().cleanBatch()).isEqualTo(1)
        assertThat(responses.findByRecord(saved.message.userId, saved.record.id)).isNull()
        assertThat(jdbc.queryForObject<String>("SELECT content FROM conversation.messages WHERE id=?", saved.message.id.value)).isEqualTo("원본")
        assertThat(generations.findByUserIdAndRecordDayId(owner, generation.recordDayId)).isNull()
    }
}
