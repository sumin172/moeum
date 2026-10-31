package com.moeum.integration

import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.query.RecordQueryService
import com.moeum.conversation.domain.*
import com.moeum.conversation.domain.model.*
import com.moeum.conversation.interfaces.*
import com.moeum.conversation.interfaces.dto.*
import com.moeum.integration.support.insertPremiumSubscription
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.*
import com.moeum.platform.job.JobStatus
import com.moeum.platform.security.jwt.JwtProvider
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.resttestclient.exchange
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.http.*
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@ActiveProfiles("local","local-all")
@AutoConfigureTestRestTemplate
@Import(AbstractIntegrationTest.FakeGoogleVerifierConfig::class,AbstractIntegrationTest.FakeConversationResponderConfig::class,
    AbstractIntegrationTest.FakeLlmProviderConfig::class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties=[
    "moeum.conversation.response-job.initial-delay=PT1H",
    "moeum.conversation.response-job.manual-retry-cooldown=0s",
    "moeum.journal.generation-job.initial-delay=PT1H","moeum.journal.delivery.initial-delay=PT1H"
])
class ConversationOrderingIntegrationTest {
    companion object {
        @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }
    }
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var jobs: ResponseJobRepository
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean lateinit var messages: MessageRepository
    @Autowired lateinit var query: RecordQueryService
    @Autowired lateinit var quota: ConversationQuotaService
    @Autowired lateinit var retry: RetryResponseService
    @Autowired lateinit var generated: SaveGeneratedResponseService
    @Autowired lateinit var failures: MarkResponseFailedService
    @Autowired lateinit var jwtProvider: JwtProvider
    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    private val now get() = Instant.now()
    @AfterEach fun finishTestJobs() { jdbc.update("UPDATE conversation.response_jobs SET status='FAILED',lease_expires_at=NULL WHERE status IN ('PENDING','PROCESSING')") }
    private fun command(content: String="기억") = SaveMessageCommand(UUID.randomUUID(),content,now,"Asia/Seoul")
    private fun accepted(user: UserId, cmd: SaveMessageCommand=command()) = save.save(user,cmd)
    private fun headers(jwt: String) = HttpHeaders().apply { setBearerAuth(jwt);contentType=MediaType.APPLICATION_JSON }
    private fun answer() = ConversationResponse("응답")
    private fun job(user: UserId, record: RecordDayId) = jobs.findByRecord(user, record)!!
    // 테스트 준비용으로 작업을 최종 실패 상태로 둔다. 재개 가능 시각은 이미 지났다.
    private fun failNow(record: RecordDayId, attempts: Int = 3) = jdbc.update(
        "UPDATE conversation.response_jobs SET status='FAILED',attempt_count=?,next_attempt_at=?,lease_expires_at=NULL,updated_at=? WHERE record_day_id=?",
        attempts, java.sql.Timestamp.from(now.minusSeconds(1)), java.sql.Timestamp.from(now.minusSeconds(1)), record.value)

    @Test fun `배치는 예약 상한 이내에서 기록당 한 작업을 선점하고 잠근 기록의 현재 원본 순번을 관측 상한으로 잡는다`() {
        val users = (1..5).map { UserId.generate() }
        users.forEach { user -> repeat(2) { accepted(user) } }
        // 사용자마다 두 메시지를 보내도 기록당 응답 작업은 하나다
        users.forEach { user ->
            assertThat(jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs WHERE user_id=?", user.value)).isEqualTo(1)
        }
        val first = jobs.claimBatch(now, now.plusSeconds(120), 3)
        val second = jobs.claimBatch(now, now.plusSeconds(120), 3)
        assertThat(first).hasSize(3)
        assertThat(second).hasSize(2)
        assertThat((first + second).map { it.recordDayId }.distinct()).hasSize(5)
        assertThat((first + second).map { it.observedSourceRevision }).containsOnly(2L)
        assertThat(jobs.claimBatch(now, now.plusSeconds(120), 3)).isEmpty()
    }

    @Test fun `최초 메시지가 동시에 도착해도 사용자 상태와 열린 기록은 하나로 생성된다`() {
        val user=UserId.generate()
        val gate=CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { pool ->
            val futures=(1..8).map { pool.submit<SaveMessageResult> { gate.await();accepted(user) } }
            gate.countDown()
            val saved=futures.map { it.get(15,TimeUnit.SECONDS) }
            assertThat(saved.map { it.record.id }.distinct()).hasSize(1)
            assertThat(saved.map { it.message.sourceSequence }.sorted()).containsExactlyElementsOf((1L..8L).toList())
            assertThat(saved.map { it.message.streamSequence }.sorted()).containsExactlyElementsOf((1L..8L).toList())
            assertThat(jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.user_record_states WHERE user_id=?",
                user.value)).isEqualTo(1)
            assertThat(query.list(user,null,10)).hasSize(1)
            assertThat(quota.current(user).used).isEqualTo(8)
            assertThat(jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs WHERE user_id=?",
                user.value)).isEqualTo(1)
        }
    }

    @Test fun `남은 한도 하나에 동시 요청해도 하나만 저장되고 재전송은 추가 차감하지 않는다`() {
        val user = UserId.generate()
        repeat(19) { accepted(user) }
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { pool ->
            val futures = (1..8).map { pool.submit<SaveMessageResult?> {
                gate.await()
                try { accepted(user) } catch (_: AiQuotaExceededException) { null }
            } }
            gate.countDown()
            val winners = futures.mapNotNull { it.get(15,TimeUnit.SECONDS) }
            assertThat(winners).hasSize(1)
            val first = winners.single()
            val duplicate = SaveMessageCommand(first.message.clientMessageId!!,first.message.content,first.message.occurredAt,"Asia/Seoul")
            assertThat(accepted(user,duplicate).message.id).isEqualTo(first.message.id)
        }
        val jwt = jwtProvider.issue(user).token
        val disclosed = rest.exchange<ConversationQuota>("/api/conversations/quota",HttpMethod.GET,HttpEntity<Void>(headers(jwt))).body!!
        assertThat(disclosed.remaining).isZero()
        assertThat(disclosed.resetAt).isEqualTo(query.list(user,null,1).single().closeAt)
        val denied = rest.exchange<QuotaErrorResponse>("/api/conversations/messages",HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(),"초과",now,"Asia/Seoul"),headers(jwt)))
        assertThat(denied.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(denied.body!!.code).isEqualTo("QUOTA_EXCEEDED")
        assertThat(denied.body!!.quota.remaining).isZero()
        val record = query.list(user,null,1).single()
        assertThat(record.sourceRevision).isEqualTo(20)
        assertThat(query.get(user,record.id,0,100).messages).hasSize(20)
        assertThat(quota.current(user)).extracting("used","remaining").containsExactly(20,0)
    }

    @Test fun `새 메시지와 수동 재시도가 동시에 와도 기록 잠금 아래에서 재개는 한 번만 일어난다`() {
        val user = UserId.generate()
        val first = accepted(user,command("실패한 입력"))
        failNow(first.record.id)
        val failed = job(user,first.record.id)
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val intake = pool.submit { gate.await();accepted(user,command("새 입력")) }
            val manual = pool.submit<ResponseJob> { gate.await();retry.retry(user,first.record.id) }
            gate.countDown();intake.get(15,TimeUnit.SECONDS);manual.get(15,TimeUnit.SECONDS)
        }
        val resumed = job(user,first.record.id)
        assertThat(resumed.state.status).isEqualTo(JobStatus.PENDING)
        // 먼저 잠금을 얻은 쪽만 전이하고 다른 쪽은 PENDING을 그대로 본다
        assertThat(resumed.state.version).isEqualTo(failed.state.version + 1)
        assertThat(resumed.state.attemptCount).isIn(0, 3)
    }

    @Test fun `실행 중 접수 뒤 최종 실패하면 재개하고 다음 실행 주기의 선점에서 관측 상한이 전진한다`() {
        val user = UserId.generate()
        val first = accepted(user,command("첫 번째"))
        val claimed = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        assertThat(claimed.observedSourceRevision).isEqualTo(1)
        accepted(user,command("실행 중 입력"))
        assertThat(job(user,first.record.id).state.status).isEqualTo(JobStatus.PROCESSING)

        failures.markFailed(claimed, com.moeum.platform.job.JobFailure(com.moeum.platform.job.JobFailureKind.PERMANENT))
        val reopened = job(user,first.record.id)
        assertThat(reopened.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(reopened.state.attemptCount).isZero()

        jdbc.update("UPDATE conversation.response_jobs SET next_attempt_at=? WHERE record_day_id=?",
            java.sql.Timestamp.from(now.minusSeconds(1)),first.record.id.value)
        assertThat(jobs.claimBatch(now,now.plusSeconds(120),1).single().observedSourceRevision).isEqualTo(2)
    }

    @Test fun `리스를 잃은 워커의 실패 저장은 거절되고 회수한 워커의 범위를 유지한다`() {
        val user = UserId.generate()
        val first = accepted(user)
        val old = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        accepted(user,command("회수 전 입력"))
        jdbc.update("UPDATE conversation.response_jobs SET lease_expires_at=? WHERE record_day_id=?",java.sql.Timestamp.from(now.minusSeconds(1)),old.recordDayId.value)
        val current = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        assertThat(current.observedSourceRevision).isEqualTo(1)
        assertThat(current.state.attemptCount).isEqualTo(2)
        assertThatThrownBy { failures.markFailed(old, com.moeum.platform.job.JobFailure(com.moeum.platform.job.JobFailureKind.PERMANENT)) }
            .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException::class.java)
        assertThat(job(user,first.record.id).state).isEqualTo(current.state)

        jdbc.update("UPDATE conversation.response_jobs SET attempt_count=5,lease_expires_at=? WHERE record_day_id=?",
            java.sql.Timestamp.from(now.minusSeconds(1)),old.recordDayId.value)
        val exhausted = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        failures.markExhausted(exhausted)
        // 회수 전 입력이 관측 상한 밖에 있으므로 호출 없이 끝난 실행도 새 실행 주기로 재개한다
        assertThat(job(user,first.record.id).state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job(user,first.record.id).state.attemptCount).isZero()
    }

    @Test fun `다중 워커의 동시 선점은 기록마다 하나이고 서로 다른 기록은 진행한다`() {
        val user = UserId.generate()
        repeat(10) { accepted(user) }
        repeat(3) { accepted(UserId.generate()) }
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(12).use { pool ->
            val futures = (1..12).map { pool.submit<ResponseJob?> { gate.await();jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull() } }
            gate.countDown()
            val claims = futures.mapNotNull { it.get(15,TimeUnit.SECONDS) }
            assertThat(claims).hasSize(4)
            assertThat(claims.map { it.recordDayId }.distinct()).hasSize(4)
            assertThat(claims.single { it.userId==user }.observedSourceRevision).isEqualTo(10)
        }
    }

    @Test fun `리스 회수 후 오래된 응답 저장은 본문과 스트림 순번까지 롤백된다`() {
        val user = UserId.generate()
        val first = accepted(user)
        val old = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        jdbc.update("UPDATE conversation.response_jobs SET lease_expires_at=? WHERE record_day_id=?",java.sql.Timestamp.from(now.minusSeconds(1)),old.recordDayId.value)
        val current = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        assertThat(current.recordDayId).isEqualTo(old.recordDayId)
        assertThatThrownBy { generated.save(old,1,answer()) }.isInstanceOf(org.springframework.dao.OptimisticLockingFailureException::class.java)
        assertThat(query.get(user,first.record.id,0,100).messages).hasSize(1)
        generated.save(current,1,answer())
        assertThat(query.get(user,first.record.id,0,100).messages.map { it.streamSequence }).containsExactly(1L,2L)
        assertThat(job(user,first.record.id).state.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(quota.current(user).used).isEqualTo(1)
    }

    @Test fun `관측 상한 밖의 접수분은 성공 뒤 다음 실행 주기로 넘어가고 성공한 응답은 과거 문맥이 된다`() {
        val user = UserId.generate()
        val first = accepted(user,command("첫 번째"))
        val job = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        accepted(user,command("두 번째"))
        accepted(user,command("세 번째"))
        assertThat(messages.findUnanswered(user,first.record.id,0,job.observedSourceRevision,100).map { it.content }).containsExactly("첫 번째")
        generated.save(job,1,answer())
        assertThat(job(user,first.record.id).state.status).isEqualTo(JobStatus.PENDING)

        val next = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        val answered = messages.answeredSourceRevision(user,first.record.id)
        assertThat(answered).isEqualTo(1)
        assertThat(messages.findUnanswered(user,first.record.id,answered,next.observedSourceRevision,100).map { it.content })
            .containsExactly("두 번째","세 번째")
        // 한 호출의 대상 수를 넘는 입력은 읽지 않는다
        assertThat(messages.findUnanswered(user,first.record.id,answered,next.observedSourceRevision,1).map { it.content })
            .containsExactly("두 번째")
        assertThat(messages.findContext(user,first.record.id,answered,100).map { it.content }).containsExactly("첫 번째","응답")
        assertThat(messages.findContext(user,first.record.id,answered,1).map { it.content }).containsExactly("응답")
    }

    @Test fun `구독 변경은 같은 기간 사용량을 유지하고 현재 한도를 적용한다`() {
        // 실제 identity 사용자로 구독 FK와 현재 권한 조회를 함께 검증한다.
        val user = UserId.generate()
        jdbc.update("INSERT INTO identity.users(id,google_id,email,created_at) VALUES (?,?,?,?)",user.value,"test-"+user.value,"test@example.com",java.sql.Timestamp.from(now))
        repeat(20) { accepted(user) }
        assertThatThrownBy { accepted(user) }.isInstanceOf(AiQuotaExceededException::class.java)
        insertPremiumSubscription(jdbc,user)
        assertThat(quota.current(user)).extracting("limit","used","remaining").containsExactly(100,20,80)
        accepted(user)
        jdbc.update("UPDATE identity.subscriptions SET revoked_at=? WHERE user_id=?",java.sql.Timestamp.from(now),user.value)
        assertThat(quota.current(user)).extracting("limit","used","remaining").containsExactly(20,21,0)
        assertThatThrownBy { accepted(user) }.isInstanceOf(AiQuotaExceededException::class.java)
    }

    @Test fun `새 메시지 없는 최종 실패를 기록 조회로 확인하고 다른 사용자 기록은 숨긴다`() {
        val user = UserId.generate()
        val jwt = jwtProvider.issue(user).token
        val first = accepted(user)
        failNow(first.record.id)
        val url = "/api/conversations/records/"+first.record.id.value
        val response = rest.exchange<RecordConversationResponse>(url,HttpMethod.GET,HttpEntity<Void>(headers(jwt)))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.response).isEqualTo(ResponseStateResponse("FAILED",true,0))
        assertThat(query.get(user,first.record.id,first.message.streamSequence,50).messages).isEmpty()
        val otherJwt = jwtProvider.issue(UserId.generate()).token
        assertThat(rest.exchange<String>(url,HttpMethod.GET,HttpEntity<Void>(headers(otherJwt))).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(rest.exchange<String>("$url/response-attempts",HttpMethod.POST,HttpEntity<Void>(headers(otherJwt))).statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
        val retried = rest.exchange<ResponseStateResponse>("$url/response-attempts",HttpMethod.POST,HttpEntity<Void>(headers(jwt)))
        assertThat(retried.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(retried.body!!.status).isEqualTo("PENDING")
        assertThat(quota.current(user).used).isEqualTo(1)
    }

    @Test fun `기록 조회 도중 워커가 답변을 완료해도 메시지·응답 상태·답변 경계를 한 스냅샷에서 반환한다`() {
        val user = UserId.generate()
        val first = accepted(user)
        val claimed = jobs.claimBatch(now,now.plusSeconds(120),1).single()
        val once = java.util.concurrent.atomic.AtomicBoolean(true)
        org.mockito.Mockito.doAnswer { invocation ->
            val page = invocation.callRealMethod()
            // 페이지를 읽은 직후 다른 트랜잭션이 답변 저장과 완료 전이를 커밋한다
            if (once.getAndSet(false)) Executors.newSingleThreadExecutor().use {
                it.submit { generated.save(claimed,1,answer()) }.get(15,TimeUnit.SECONDS)
            }
            page
        }.`when`(messages).findPage(user,first.record.id,0,100)

        val during = query.get(user,first.record.id,0,100)
        assertThat(during.messages.map { it.role }).containsExactly(MessageRole.USER)
        assertThat(during.response.job!!.state.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(during.response.answeredSourceRevision).isZero()

        val after = query.get(user,first.record.id,0,100)
        assertThat(after.messages.map { it.role }).containsExactly(MessageRole.USER,MessageRole.ASSISTANT)
        assertThat(after.response.job!!.state.status).isEqualTo(JobStatus.COMPLETED)
        assertThat(after.response.answeredSourceRevision).isEqualTo(1)
    }

    @Test fun `저장 잠금의 커밋 순번으로 동시 원본과 응답을 증분 조회한다`() {
        val user = UserId.generate()
        val first = accepted(user)
        val claimed = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val a = pool.submit { gate.await();accepted(user,command("동시 원본")) }
            val b = pool.submit { gate.await();generated.save(claimed,1,answer()) }
            gate.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS)
        }
        val page = query.get(user,first.record.id,first.message.streamSequence,100).messages
        assertThat(page.map { it.streamSequence }.sorted()).containsExactly(2L,3L)
        assertThat(page.map { it.role }).containsExactlyInAnyOrder(MessageRole.USER,MessageRole.ASSISTANT)
        assertThat(query.get(user,first.record.id,3,100).messages).isEmpty()
    }
}
