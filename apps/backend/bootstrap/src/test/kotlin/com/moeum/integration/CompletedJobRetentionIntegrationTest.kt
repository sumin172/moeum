package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.application.publicapi.RecordDelivery
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.infrastructure.scheduling.CompletedResponseJobCleaner
import com.moeum.journal.application.command.RegisterRecordGenerationService
import com.moeum.journal.application.command.RetryJournalGenerationService
import com.moeum.journal.domain.*
import com.moeum.journal.domain.model.*
import com.moeum.journal.infrastructure.scheduling.CompletedGenerationJobCleaner
import com.moeum.kernel.*
import com.moeum.platform.job.CompletedJobRetentionProperties
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.*
import java.util.UUID
import java.util.concurrent.*

class CompletedJobRetentionIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var responses: ResponseJobRepository
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
    private val props = CompletedJobRetentionProperties(batchSize=2)
    private fun user() = UserId.generate().also(users::add)
    private fun input(user: UserId) = save.save(user, SaveMessageCommand(UUID.randomUUID(), "원본",
        now.minus(Duration.ofDays(1)), "Asia/Seoul")) as SaveMessageResult.Accepted
    private fun response(saved: SaveMessageResult.Accepted, complete: Boolean = true): ResponseJob {
        val pending = ResponseJob.pending(saved.message, now.plus(Duration.ofDays(10)))
        return responses.save(if (complete) pending.completed(old) else pending)
    }
    private fun responseCleaner() = CompletedResponseJobCleaner(jdbc, time, props)
    private fun generationCleaner() = CompletedGenerationJobCleaner(jdbc, time, props)

    @AfterEach fun cleanupFixture() {
        users.forEach {
            jdbc.update("DELETE FROM conversation.response_jobs WHERE user_id=?", it.value)
            jdbc.update("DELETE FROM journal.generation_jobs WHERE user_id=?", it.value)
            jdbc.update("DELETE FROM journal.journals WHERE user_id=?", it.value)
        }
    }

    @Test fun `동결 완료 작업만 작은 배치로 지우고 원본 중복 접수와 결과 조회는 보존한다`() {
        val saved = (1..3).map { input(user()) }
        val ids = saved.map { response(it).id.value }
        saved.forEach { records.freezeInput(it.message.userId, it.record.id) }
        assertThat(responseCleaner().cleanBatch()).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation.response_jobs WHERE id IN (?,?,?)",
            Int::class.java, *ids.toTypedArray())).isEqualTo(1)
        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
        saved.forEach {
            assertThat(jdbc.queryForObject("SELECT content FROM conversation.messages WHERE id=?", String::class.java, it.message.id.value)).isEqualTo("원본")
            val duplicate = save.save(it.message.userId, SaveMessageCommand(it.message.clientMessageId!!, it.message.content,
                it.message.occurredAt, it.message.timezone)) as SaveMessageResult.Accepted
            assertThat(duplicate.message.id).isEqualTo(it.message.id)
            assertThat(duplicate.responseJob).isNull()
        }
    }

    @Test fun `미동결 최근 완료 활성 실패 작업과 실패 작업의 후속 완료를 보존한다`() {
        val unfrozen = input(user())
        val unfrozenJob = response(unfrozen)
        val recent = input(user())
        val recentJob = responses.save(ResponseJob.pending(recent.message, now).completed(now))
        records.freezeInput(recent.message.userId, recent.record.id)
        val pending = input(user())
        val pendingJob = response(pending, complete=false)
        records.freezeInput(pending.message.userId, pending.record.id)
        val processing = input(user())
        val processingJob = response(processing, complete=false)
        jdbc.update("UPDATE conversation.response_jobs SET status='PROCESSING',lease_expires_at=? WHERE id=?",
            Timestamp.from(now.plus(Duration.ofDays(10))), processingJob.id.value)
        records.freezeInput(processing.message.userId, processing.record.id)
        val failedFirst = input(user())
        val failedJob = responses.save(ResponseJob.pending(failedFirst.message, now).failedPermanently("TEST", old))
        val later = input(failedFirst.message.userId)
        val laterJob = response(later)
        records.freezeInput(later.message.userId, later.record.id)
        assertThat(responseCleaner().cleanBatch()).isZero()
        listOf(unfrozenJob, recentJob, pendingJob, processingJob, failedJob, laterJob).forEach {
            assertThat(responses.findByUserIdAndUserMessageId(it.userId, it.userMessageId)).isNotNull()
        }
        assertThat(responses.hasLaterStarted(failedJob)).isTrue()
    }

    @Test fun `정리 작업은 잠긴 기록을 건너뛰고 잠금 해제 이후 정리한다`() {
        val saved = input(user())
        response(saved)
        records.freezeInput(saved.message.userId, saved.record.id)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { pool ->
            val lock = pool.submit {
                TransactionTemplate(transactions).executeWithoutResult {
                    jdbc.queryForObject("SELECT id FROM conversation.record_days WHERE id=? FOR UPDATE", UUID::class.java, saved.record.id.value)
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
        val original = journals.insertGenerated(Journal(JournalId.generate(), user, day, date, "제목", "본문", 0,
            7, UUID.randomUUID(), "model", "prompt-v1", old))
        val edited = journals.updateEdited(original.copy(body="사용자 수정본"))
        val pending = GenerationJob.pending(user, day, date, now.plus(Duration.ofDays(10)), old)
        generations.save(pending.completed(original.id, old))
        val failed = GenerationJob.pending(user, RecordDayId.generate(), date, now.plus(Duration.ofDays(10)), old)
            .failedPermanently("TEST", old)
        generations.save(failed)
        val processing = generations.save(GenerationJob.pending(user, RecordDayId.generate(), date, now.plus(Duration.ofDays(10)), old))
        jdbc.update("UPDATE journal.generation_jobs SET status='PROCESSING',lease_expires_at=? WHERE id=?",
            Timestamp.from(now.plus(Duration.ofDays(10))), processing.id.value)
        val recent = generations.save(GenerationJob.pending(user, RecordDayId.generate(), date, now.plus(Duration.ofDays(10)), now))
        assertThat(generationCleaner().cleanBatch()).isEqualTo(1)
        assertThat(journals.findByUserIdAndRecordDayId(user, day)).isEqualTo(edited)
        assertThat(generations.findByUserIdAndRecordDayId(user, day)).isNull()
        assertThat(registration.register(RecordDelivery(day, user, date, old, 1))).isFalse()
        assertThatThrownBy { retry.retry(user, day) }.isInstanceOf(JournalGenerationNotRetryableException::class.java)
        listOf(failed, processing, recent).forEach { assertThat(generations.findByUserIdAndRecordDayId(user, it.recordDayId)).isNotNull() }
    }
}
