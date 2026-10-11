package com.moeum.integration
import com.moeum.conversation.application.command.*
import com.moeum.conversation.application.publicapi.*
import com.moeum.conversation.application.query.RecordQueryService
import com.moeum.conversation.domain.*
import com.moeum.conversation.interfaces.CloseTimeRequest
import com.moeum.conversation.interfaces.dto.*
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.application.command.RegisterRecordGenerationService
import com.moeum.kernel.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.*
import org.springframework.jdbc.core.JdbcTemplate
import java.time.*
import java.util.UUID
import java.util.concurrent.*
class DayBoundaryIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var query: RecordQueryService
    @Autowired lateinit var registration: RegisterRecordGenerationService
    @Autowired lateinit var jdbc: JdbcTemplate
    private fun command(at: Instant, id: UUID = UUID.randomUUID(), record: RecordDayId? = null) =
        SaveMessageCommand(id,"원본",at,"Asia/Seoul",record)
    private fun accepted(user: UserId, command: SaveMessageCommand) = save.save(user,command) as SaveMessageResult.Accepted
    private fun count(table: String, user: UserId) = jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id = ?",Int::class.java,user.value)!!
    @Test fun `설정은 즉시 저장하지만 이미 열린 기록의 마감 스냅샷은 유지한다`() {
        val jwt = issueJwt()
        val initial = restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences",HttpMethod.GET,HttpEntity<Void>(authHeaders(jwt))).body!!
        assertThat(initial.closeTime).isEqualTo(LocalTime.of(2,0))
        val saved = restTemplate.exchange<com.moeum.conversation.interfaces.SaveMessageResponse>("/api/conversations/messages",HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(),"스냅샷",Instant.now(),"Asia/Seoul"),authHeaders(jwt))).body!!
        restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences",HttpMethod.PUT,HttpEntity(CloseTimeRequest(LocalTime.of(4,0)),authHeaders(jwt)))
        assertThat(jdbc.queryForObject("SELECT close_time FROM conversation.record_days WHERE id = ?",LocalTime::class.java,saved.recordDayId)).isEqualTo(LocalTime.of(2,0))
        assertThat(restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences",HttpMethod.GET,HttpEntity<Void>(authHeaders(jwt))).body!!.closeTime).isEqualTo(LocalTime.of(4,0))
    }
    @Test fun `7일보다 오래된 원본도 동결 전에는 과거 기록에 저장되고 AI 응답은 생성하지 않는다`() {
        val user = UserId.generate()
        val first = accepted(user,command(Instant.now().minus(Duration.ofDays(10))))
        assertThat(first.record.closedAt).isNotNull()
        assertThat(first.responseJob).isNull()
        assertThat(count("conversation.messages",user)).isEqualTo(1)
        assertThat(records.freezeInput(user,first.record.id).messages.map { it.content }).containsExactly("원본")
    }
    @Test fun `동시 중복 요청은 메시지 원본 버전 quota 작업을 각각 한번만 만든다`() {
        val user = UserId.generate()
        val command = command(Instant.now())
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { pool ->
            val results = (1..8).map { pool.submit<SaveMessageResult.Accepted> { start.await();accepted(user,command) } }
            start.countDown()
            assertThat(results.map { it.get(10,TimeUnit.SECONDS).message.id }.distinct()).hasSize(1)
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation.messages WHERE user_id = ? AND role = 'USER'",Int::class.java,user.value)).isEqualTo(1)
        assertThat(count("conversation.response_jobs",user)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT source_revision FROM conversation.record_days WHERE user_id = ?",Long::class.java,user.value)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT message_count FROM conversation.ai_usage_daily WHERE user_id = ?",Int::class.java,user.value)).isEqualTo(1)
    }
    @Test fun `동결 이후 신규 입력은 모두 폐기되고 이미 저장된 중복은 성공을 돌려준다`() {
        val user = UserId.generate()
        val command = command(Instant.now().minus(Duration.ofDays(1)))
        val first = accepted(user,command)
        val input = records.freezeInput(user,first.record.id)
        val usage = jdbc.queryForObject("SELECT message_count FROM conversation.ai_usage_daily WHERE user_id = ?",Int::class.java,user.value)
        val late = command.copy(clientMessageId = UUID.randomUUID())
        assertThat(save.save(user,late)).isInstanceOf(SaveMessageResult.Discarded::class.java)
        assertThat(save.save(user,late)).isInstanceOf(SaveMessageResult.Discarded::class.java)
        assertThat(accepted(user,command).message.id).isEqualTo(first.message.id)
        assertThat(records.freezeInput(user,first.record.id)).isEqualTo(input)
        assertThat(count("conversation.messages",user)).isEqualTo(1)
        assertThat(count("conversation.response_jobs",user)).isZero()
        assertThat(jdbc.queryForObject("SELECT message_count FROM conversation.ai_usage_daily WHERE user_id = ?",Int::class.java,user.value)).isEqualTo(usage)
    }
    @Test fun `추가와 동결 경합은 저장 또는 폐기 중 하나이며 재시도 입력은 동일하다`() {
        val user = UserId.generate()
        val first = accepted(user,command(Instant.now().minus(Duration.ofDays(1))))
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val freeze = pool.submit<FrozenRecordInput> { gate.await();records.freezeInput(user,first.record.id) }
            val append = pool.submit<SaveMessageResult> { gate.await();save.save(user,command(first.message.occurredAt.plusSeconds(1),record=first.record.id)) }
            gate.countDown()
            val frozen = freeze.get(10,TimeUnit.SECONDS)
            val outcome = append.get(10,TimeUnit.SECONDS)
            assertThat(frozen.messages.size).isEqualTo(if(outcome is SaveMessageResult.Accepted) 2 else 1)
            assertThat(records.freezeInput(user,first.record.id)).isEqualTo(frozen)
            assertThat(count("conversation.messages",user)).isEqualTo(frozen.messages.size)
        }
    }
    @Test fun `전달 확인 전에 재전달되어도 생성 작업은 하나이고 이전 버전 확인은 실패한다`() {
        val user = UserId.generate()
        val first = accepted(user,command(Instant.now().minus(Duration.ofDays(1))))
        fun claim() = generateSequence { records.claimDelivery() }.first { it.recordDayId == first.record.id }
        val delivery = claim()
        assertThat(registration.register(delivery)).isTrue()
        jdbc.update("UPDATE conversation.record_days SET delivery_lease_expires_at = now() - interval '1 second' WHERE id = ?",first.record.id.value)
        val second = claim()
        assertThat(second.version).isGreaterThan(delivery.version)
        assertThat(registration.register(second)).isFalse()
        assertThat(records.acknowledge(delivery)).isFalse()
        assertThat(records.acknowledge(second)).isTrue()
        assertThat(count("journal.generation_jobs",user)).isEqualTo(1)
    }
    @Test fun `작업 등록 후 대기 중 도착한 원본은 첫 입력에 포함된다`() {
        val user = UserId.generate()
        val first = accepted(user,command(Instant.now().minus(Duration.ofDays(1))))
        val delivery = RecordDelivery(first.record.id,user,first.record.displayDate,first.record.closedAt!!,1)
        registration.register(delivery)
        accepted(user,command(first.message.occurredAt.plusSeconds(1),record=first.record.id))
        val frozen = records.freezeInput(user,first.record.id)
        assertThat(frozen.sourceRevision).isEqualTo(2)
        assertThat(frozen.messages).hasSize(2)
        assertThat(count("journal.generation_jobs",user)).isEqualTo(1)
    }
    @Test fun `마감 처리보다 늦은 API는 마감 정각 이후 발화를 다음 기록에 넣는다`() {
        val user = UserId.generate()
        val first = accepted(user,command(Instant.now()))
        val boundary = first.message.occurredAt.plusMillis(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        jdbc.update("UPDATE conversation.record_days SET close_at = ? WHERE id = ?",java.sql.Timestamp.from(boundary),first.record.id.value)
        Thread.sleep(20)
        val next = accepted(user,command(Instant.now()))
        assertThat(next.record.id).isNotEqualTo(first.record.id)
        assertThat(next.record.startAt).isEqualTo(boundary.truncatedTo(java.time.temporal.ChronoUnit.MICROS))
        assertThat(query.get(user,first.record.id,null,50).record.closedAt).isEqualTo(boundary.truncatedTo(java.time.temporal.ChronoUnit.MICROS))
    }
    @Test fun `마감과 이전 발화 저장 경합은 동일 기록의 원본을 보존한다`() {
        val user = UserId.generate()
        val first = accepted(user,command(Instant.now()))
        jdbc.update("UPDATE conversation.record_days SET close_at = ? WHERE id = ?",java.sql.Timestamp.from(first.message.occurredAt.plusMillis(10)),first.record.id.value)
        Thread.sleep(20)
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val close = pool.submit { gate.await();records.closeNext() }
            val append = pool.submit<SaveMessageResult.Accepted> { gate.await();accepted(user,command(first.message.occurredAt,record=first.record.id)) }
            gate.countDown()
            close.get(10,TimeUnit.SECONDS)
            assertThat(append.get(10,TimeUnit.SECONDS).record.id).isEqualTo(first.record.id)
        }
        assertThat(records.freezeInput(user,first.record.id).messages).hasSize(2)
    }
    @Test fun `활동이 없으면 조회와 마감 검사만으로 빈 기록과 일기는 생기지 않는다`() {
        val user = UserId.generate()
        assertThat(query.list(user,null,50)).isEmpty()
        assertThat(query.closeTime(user)).isEqualTo(LocalTime.of(2,0))
        assertThat(count("conversation.record_days",user)).isZero()
        assertThat(count("journal.generation_jobs",user)).isZero()
    }
    @Test fun `다른 사용자 기록 힌트와 미래 5분 초과 입력은 거부한다`() {
        val first = accepted(UserId.generate(),command(Instant.now()))
        assertThatThrownBy { save.save(UserId.generate(),command(first.message.occurredAt,record=first.record.id)) }.isInstanceOf(RecordDayNotFoundException::class.java)
        assertThatThrownBy { save.save(UserId.generate(),command(Instant.now().plusSeconds(301))) }.isInstanceOf(InvalidConversationRequestException::class.java)
    }
}
