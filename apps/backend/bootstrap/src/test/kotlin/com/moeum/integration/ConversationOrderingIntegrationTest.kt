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
    @Autowired lateinit var messages: MessageRepository
    @Autowired lateinit var query: RecordQueryService
    @Autowired lateinit var quota: ConversationQuotaService
    @Autowired lateinit var retry: RetryResponseService
    @Autowired lateinit var generated: SaveGeneratedResponseService
    @Autowired lateinit var jwtProvider: JwtProvider
    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    private val now get() = Instant.now()
    @AfterEach fun finishTestJobs() { jdbc.update("UPDATE conversation.response_jobs SET status='FAILED',lease_expires_at=NULL WHERE status IN ('PENDING','PROCESSING')") }
    private fun command(content: String="기억") = SaveMessageCommand(UUID.randomUUID(),content,now,"Asia/Seoul")
    private fun accepted(user: UserId, cmd: SaveMessageCommand=command()) = save.save(user,cmd)
    private fun headers(jwt: String) = HttpHeaders().apply { setBearerAuth(jwt);contentType=MediaType.APPLICATION_JSON }
    private fun answer() = ConversationResponse("응답")

    @Test fun `배치는 예약 상한 이내에서 기록별 선두만 선점하고 다음 워커와 중복하지 않는다`() {
        val users = (1..5).map { UserId.generate() }
        users.forEach { user -> repeat(2) { accepted(user) } }
        val first = jobs.claimBatch(now, now.plusSeconds(120), 3)
        val second = jobs.claimBatch(now, now.plusSeconds(120), 3)
        assertThat(first).hasSize(3)
        assertThat(second).hasSize(2)
        assertThat((first + second).map { it.recordDayId }.distinct()).hasSize(5)
        assertThat((first + second).map { it.inputSourceRevision }).containsOnly(1L)
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

    @Test fun `같은 기록은 선행 실패가 끝나야 진행하고 후속 실행 뒤 과거 재시도는 거부한다`() {
        val user = UserId.generate()
        val first = accepted(user,command("첫 번째"))
        val second = accepted(user,command("두 번째"))
        val claimed = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        assertThat(claimed.id).isEqualTo(first.responseJob!!.id)
        val other = accepted(UserId.generate(),command("다른 기록"))
        val parallel = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        assertThat(parallel.id).isEqualTo(other.responseJob!!.id)
        assertThat(jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()).isNull()
        jobs.save(claimed.copy(state=claimed.state.copy(status=JobStatus.PENDING,nextAttemptAt=now.plusSeconds(60),leaseExpiresAt=null)))
        assertThat(jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()).isNull()
        val pending = jobs.findByUserIdAndUserMessageId(user,first.message.id)!!
        jobs.save(pending.failedPermanently(now))
        val next = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        assertThat(next.id).isEqualTo(second.responseJob!!.id)
        assertThatThrownBy { retry.retry(user,first.message.id) }.isInstanceOf(ResponseSupersededException::class.java)
        generated.save(next,second.message,answer())
        assertThatThrownBy { retry.retry(user,first.message.id) }.isInstanceOf(ResponseSupersededException::class.java)
        assertThat(quota.current(user).used).isEqualTo(2)
        val jwt = jwtProvider.issue(user).token
        val status = rest.exchange<List<ResponseJobStatusResponse>>("/api/conversations/response-jobs?ids="+first.responseJob!!.id.value,
            HttpMethod.GET,HttpEntity<Void>(headers(jwt))).body!!.single()
        assertThat(status.retryable).isFalse()
        assertThat(RecordConversationResponse.from(query.get(user,first.record.id,0,100)).messages.first { it.id==first.message.id.value }.retryable).isFalse()
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
            assertThat(claims.single { it.userId==user }.inputSourceRevision).isEqualTo(1)
        }
    }

    @Test fun `리스 회수 후 오래된 응답 저장은 본문과 스트림 순번까지 롤백된다`() {
        val user = UserId.generate()
        val first = accepted(user)
        val old = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        jdbc.update("UPDATE conversation.response_jobs SET lease_expires_at=? WHERE id=?",java.sql.Timestamp.from(now.minusSeconds(1)),old.id.value)
        val current = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        assertThat(current.id).isEqualTo(old.id)
        assertThatThrownBy { generated.save(old,first.message,answer()) }.isInstanceOf(org.springframework.dao.OptimisticLockingFailureException::class.java)
        assertThat(query.get(user,first.record.id,0,100).messages).hasSize(1)
        generated.save(current,first.message,answer())
        assertThat(query.get(user,first.record.id,0,100).messages.map { it.streamSequence }).containsExactly(1L,2L)
        assertThat(quota.current(user).used).isEqualTo(1)
    }

    @Test fun `작업 입력은 후속 원본을 제외하고 성공한 선행 응답을 포함하며 조회 건수를 제한한다`() {
        val user = UserId.generate()
        val first = accepted(user,command("첫 번째"))
        val second = accepted(user,command("두 번째"))
        val third = accepted(user,command("세 번째"))
        val job = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        assertThat(messages.findContext(user,first.record.id,job.inputSourceRevision,100).map { it.content }).containsExactly("첫 번째")
        generated.save(job,first.message,answer())
        assertThat(messages.findContext(user,first.record.id,second.responseJob!!.inputSourceRevision,100).map { it.content })
            .containsExactly("첫 번째","응답","두 번째")
        assertThat(messages.findContext(user,first.record.id,third.responseJob!!.inputSourceRevision,2).map { it.content })
            .containsExactly("두 번째","세 번째")
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

    @Test fun `메시지 없는 최종 실패를 작업 묶음으로 조회하며 다른 사용자 작업은 숨긴다`() {
        val user = UserId.generate()
        val jwt = jwtProvider.issue(user).token
        val first = accepted(user)
        jobs.save(first.responseJob!!.failedPermanently(now))
        val url = "/api/conversations/response-jobs?ids="+first.responseJob!!.id.value
        val response = rest.exchange<List<ResponseJobStatusResponse>>(url,HttpMethod.GET,HttpEntity<Void>(headers(jwt)))
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.single().status).isEqualTo("FAILED")
        assertThat(query.get(user,first.record.id,first.message.streamSequence,50).messages).isEmpty()
        val otherJwt = jwtProvider.issue(UserId.generate()).token
        assertThat(rest.exchange<String>(url,HttpMethod.GET,HttpEntity<Void>(headers(otherJwt))).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val retried = retry.retry(user,first.message.id)
        assertThat(retried.responseJob.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(quota.current(user).used).isEqualTo(1)
    }

    @Test fun `저장 잠금의 커밋 순번으로 동시 원본과 응답을 증분 조회한다`() {
        val user = UserId.generate()
        val first = accepted(user)
        val claimed = jobs.claimBatch(now,now.plusSeconds(120),1).singleOrNull()!!
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val a = pool.submit { gate.await();accepted(user,command("동시 원본")) }
            val b = pool.submit { gate.await();generated.save(claimed,first.message,answer()) }
            gate.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS)
        }
        val page = query.get(user,first.record.id,first.message.streamSequence,100).messages
        assertThat(page.map { it.streamSequence }.sorted()).containsExactly(2L,3L)
        assertThat(page.map { it.role }).containsExactlyInAnyOrder(MessageRole.USER,MessageRole.ASSISTANT)
        assertThat(query.get(user,first.record.id,3,100).messages).isEmpty()
    }
}
