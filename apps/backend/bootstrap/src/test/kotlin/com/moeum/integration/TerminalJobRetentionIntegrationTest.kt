package com.moeum.integration

import com.moeum.journal.domain.model.failedPermanently

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.application.publicapi.RecordDelivery
import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.ResponseSupersededException
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.application.query.ResponseStatusQueryService
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
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var responses: ResponseJobRepository
    @Autowired lateinit var messages: MessageRepository
    @Autowired lateinit var retryResponse: RetryResponseService
    @Autowired lateinit var responseStatuses: ResponseStatusQueryService
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
        if (close) {
            val boundary = Timestamp.from(Instant.now())
            jdbc.update("UPDATE conversation.record_days SET close_at=?,closed_at=?,delivery_acknowledged=true WHERE id=?",
                boundary, boundary, saved.record.id.value)
        }
        return saved
    }
    private fun response(saved: SaveMessageResult, complete: Boolean = true): ResponseJob {
        val pending = saved.responseJob!!
        if (complete) jdbc.update("UPDATE conversation.response_jobs SET status='COMPLETED',updated_at=?,version=version+1 WHERE id=?",
            Timestamp.from(old), pending.id.value)
        return responses.findByUserIdAndUserMessageId(pending.userId, pending.userMessageId)!!
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
        val ids = saved.map { response(it).id.value }
        assertThat(responseCleaner().cleanBatch()).isEqualTo(2)
        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM conversation.response_jobs WHERE id IN (?,?,?)",
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
        val responseIds = (1..5).map { response(input(user())).id.value }
        val generationIds = (1..5).map {
            val owner = user()
            generations.save(TestGenerationJobs.pending(owner, RecordDayId.generate(), now, old).completed(old)).id.value
        }

        responseCleaner().scheduledCleanup()
        generationCleaner().scheduledCleanup()

        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM conversation.response_jobs WHERE id IN (?,?,?,?,?)",
            *responseIds.toTypedArray())).isZero()
        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE id IN (?,?,?,?,?)",
            *generationIds.toTypedArray())).isZero()
    }

    @Test fun `실패 작업을 보존하면서 후속 완료 작업은 정리하고 원본 응답으로 재시도를 막는다`() {
        val open = input(user(), close = false)
        val openJob = response(open)
        val recent = input(user())
        jdbc.update("UPDATE conversation.response_jobs SET status='COMPLETED',updated_at=? WHERE id=?",
            Timestamp.from(now), recent.responseJob!!.id.value)
        val recentJob = responses.findByUserIdAndUserMessageId(recent.message.userId, recent.message.id)!!
        val pending = input(user())
        val pendingJob = response(pending, complete=false)
        val processing = input(user())
        val processingJob = response(processing, complete=false)
        jdbc.update("UPDATE conversation.response_jobs SET status='PROCESSING',lease_expires_at=? WHERE id=?",
            Timestamp.from(now.plus(Duration.ofDays(10))), processingJob.id.value)
        val failedFirst = input(user(), close=false)
        val later = input(failedFirst.message.userId, close=false)
        val boundary = Timestamp.from(Instant.now())
        jdbc.update("UPDATE conversation.record_days SET close_at=?,closed_at=?,delivery_acknowledged=true WHERE id=?",
            boundary, boundary, failedFirst.record.id.value)
        jdbc.update("UPDATE conversation.response_jobs SET status='FAILED',updated_at=? WHERE id=?",
            Timestamp.from(now), failedFirst.responseJob!!.id.value)
        val failedJob = responses.findByUserIdAndUserMessageId(failedFirst.message.userId, failedFirst.message.id)!!
        val laterJob = response(later)
        TransactionTemplate(transactions).executeWithoutResult {
            messages.append(Message.assistantMessage(MessageId.generate(),later.message.userId,"후속 답변",now,
                later.message.recordDayId,later.message.sourceSequence))
        }
        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
        listOf(openJob, recentJob, pendingJob, processingJob, failedJob).forEach {
            assertThat(responses.findByUserIdAndUserMessageId(it.userId, it.userMessageId)).isNotNull()
        }
        assertThat(responses.findByUserIdAndUserMessageId(laterJob.userId,laterJob.userMessageId)).isNull()
        assertThat(responses.hasLaterStarted(failedJob)).isTrue()
        assertThat(responseStatuses.get(failedFirst.message.userId,listOf(failedJob.id)).single().retryable).isFalse()
        assertThatThrownBy { retryResponse.retry(failedFirst.message.userId,failedFirst.message.id) }
            .isInstanceOf(ResponseSupersededException::class.java)
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
        jdbc.update("UPDATE conversation.response_jobs SET status='FAILED',updated_at=? WHERE id=?",
            Timestamp.from(old), saved.responseJob!!.id.value)
        val owner = user()
        val generation = generations.save(TestGenerationJobs.pending(owner, RecordDayId.generate(), now, old).failedPermanently(old))

        assertThat(responseCleaner().cleanBatch()).isEqualTo(1)
        assertThat(generationCleaner().cleanBatch()).isEqualTo(1)
        assertThat(responses.findByUserIdAndUserMessageId(saved.message.userId, saved.message.id)).isNull()
        assertThat(messages.findByUserIdAndId(saved.message.userId, saved.message.id)).isNotNull()
        assertThat(generations.findByUserIdAndRecordDayId(owner, generation.recordDayId)).isNull()
    }
}
