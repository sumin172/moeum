package com.moeum.integration

import com.moeum.conversation.application.command.SaveMessageCommand
import com.moeum.conversation.application.command.SaveMessageResult
import com.moeum.conversation.application.command.SaveMessageService
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.application.query.RecordQueryService
import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.interfaces.CloseTimeRequest
import com.moeum.conversation.interfaces.SaveMessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.application.command.RegisterRecordGenerationService
import com.moeum.kernel.UserId
import com.moeum.kernel.TimeProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.`when`
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.TestPropertySource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestPropertySource(properties = ["moeum.conversation.response-job.initial-delay=PT1H"])
class DayBoundaryIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var quota: com.moeum.conversation.application.command.ConversationQuotaService
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var query: RecordQueryService
    @Autowired lateinit var registration: RegisterRecordGenerationService
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoBean lateinit var time: TimeProvider

    @BeforeEach fun useCurrentTime() { `when`(time.now()).thenAnswer { Instant.now() } }

    private fun command(at: Instant = Instant.now(), id: UUID = UUID.randomUUID()) =
        SaveMessageCommand(id, "원본", at, "Asia/Seoul")
    private fun accepted(user: UserId, command: SaveMessageCommand = command()) =
        save.save(user, command)
    private fun count(table: String, user: UserId) =
        jdbc.queryForObject<Int>("SELECT count(*) FROM $table WHERE user_id=?", user.value)!!
    private fun countUserMessages(user: UserId) =
        jdbc.queryForObject<Int>("SELECT count(*) FROM conversation.messages WHERE user_id=? AND role='USER'", user.value)!!
    private fun close(day: UUID) {
        val boundary = Timestamp.from(Instant.now())
        jdbc.update("UPDATE conversation.record_days SET close_at=?,closed_at=? WHERE id=?", boundary, boundary, day)
    }

    @Test fun `마감 설정 변경은 현재 기록을 유지하고 다음 기록부터 적용한다`() {
        val jwt = issueJwt()
        val initial = restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences", HttpMethod.GET,
            HttpEntity<Void>(authHeaders(jwt))).body!!
        assertThat(initial.closeTime).isEqualTo(LocalTime.of(2, 0))
        val saved = restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages", HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(), "스냅샷", Instant.now(), "Asia/Seoul"), authHeaders(jwt))).body!!
        val changed = restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences", HttpMethod.PUT,
            HttpEntity(CloseTimeRequest(LocalTime.of(4, 0)), authHeaders(jwt)))
        assertThat(changed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(jdbc.queryForObject<LocalTime>("SELECT close_time FROM conversation.record_days WHERE id=?",
            saved.recordDayId)).isEqualTo(LocalTime.of(2, 0))
        val deadline = jdbc.queryForObject<Timestamp>("SELECT close_at FROM conversation.record_days WHERE id=?",saved.recordDayId)!!.toInstant()
        `when`(time.now()).thenReturn(deadline)
        val next = restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages", HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(), "다음 기록", Instant.now(), "Asia/Seoul"), authHeaders(jwt))).body!!
        assertThat(next.recordDayId).isNotEqualTo(saved.recordDayId)
        assertThat(jdbc.queryForObject<LocalTime>("SELECT close_time FROM conversation.record_days WHERE id=?",next.recordDayId))
            .isEqualTo(LocalTime.of(4, 0))
        assertThat(jdbc.queryForObject<Timestamp>("SELECT close_at FROM conversation.record_days WHERE id=?",next.recordDayId)!!.toInstant())
            .isEqualTo(deadline.plusSeconds(26 * 60 * 60))
        assertThat(jdbc.queryForObject<Timestamp>("SELECT closed_at FROM conversation.record_days WHERE id=?",saved.recordDayId)!!.toInstant())
            .isEqualTo(deadline)
    }

    @Test fun `허용 시간 밖의 마감 설정은 400이고 저장 설정은 유지된다`() {
        val jwt = issueJwt()
        listOf("18:00", "00:00", "04:00").forEach { value ->
            val response = restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences",HttpMethod.PUT,
                HttpEntity(CloseTimeRequest(LocalTime.parse(value)),authHeaders(jwt)))
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        }
        listOf("04:00:01", "12:00", "17:59:59").forEach { value ->
            val response = restTemplate.exchange<com.moeum.platform.web.ErrorResponse>("/api/conversations/preferences",HttpMethod.PUT,
                HttpEntity(CloseTimeRequest(LocalTime.parse(value)),authHeaders(jwt)))
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        val current = restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences",HttpMethod.GET,
            HttpEntity<Void>(authHeaders(jwt))).body!!
        assertThat(current.closeTime).isEqualTo(LocalTime.of(4,0))
    }

    @Test fun `미국에서 한국으로 이동한 새 대화는 이전 일기 입력에서 분리되고 중복 접수에도 유지된다`() {
        val user = UserId.generate()
        val before = Instant.parse("2026-08-02T05:00:00Z")
        `when`(time.now()).thenReturn(before)
        val first = accepted(user,command(before).copy(timezone="America/Los_Angeles",content="미국의 밤"))
        val arrival = before.plusSeconds(60)
        `when`(time.now()).thenReturn(arrival)
        val request = command(arrival).copy(content="한국의 오후")
        val next = accepted(user,request)
        val retried = accepted(user,request)

        assertThat(next.record.id).isNotEqualTo(first.record.id)
        assertThat(next.responseJob).isNotNull()
        assertThat(retried.message.id).isEqualTo(next.message.id)
        assertThat(records.readJournalInput(user,first.record.id).messages.filter { it.role=="USER" }.map { it.content })
            .containsExactly("미국의 밤")
        assertThat(next.record.startAt).isEqualTo(arrival)
        assertThat(countUserMessages(user)).isEqualTo(2)
        assertThat(count("conversation.response_jobs",user)).isEqualTo(2)
        assertThat(quota.current(user).used).isEqualTo(1)
        assertThat(jdbc.queryForObject<Int>("SELECT request_count FROM conversation.conversation_usage WHERE user_id=?",user.value))
            .isEqualTo(1)
    }

    @Test fun `여행으로 기록이 일찍 닫히면 새 기록에 대화 한도를 다시 제공한다`() {
        val user = UserId.generate()
        val before = Instant.parse("2026-08-02T05:00:00Z")
        `when`(time.now()).thenReturn(before)
        val first = (1..20).map { accepted(user,command(before).copy(timezone="America/Los_Angeles")) }.last().record
        assertThat(quota.current(user).remaining).isZero()
        `when`(time.now()).thenReturn(before.plusSeconds(60))
        val next = accepted(user,command(before.plusSeconds(60)))
        assertThat(next.record.id).isNotEqualTo(first.id)
        assertThat(quota.current(user)).extracting("used","remaining").containsExactly(1,19)
        assertThat(jdbc.queryForObject<Long>("SELECT source_revision FROM conversation.record_days WHERE id=?",first.id.value)).isEqualTo(20)
    }

    @Test fun `열린 기록이 없으면 전체 한도가 남고 초기화 시각은 없다`() {
        val user = UserId.generate()
        assertThat(quota.current(user)).extracting("used","remaining","resetAt").containsExactly(0,20,null)
        val first = accepted(user)
        assertThat(quota.current(user).resetAt).isEqualTo(first.record.closeAt)
        close(first.record.id.value)
        assertThat(quota.current(user)).extracting("used","remaining","resetAt").containsExactly(0,20,null)
    }

    @Test fun `오래된 발화 시각이어도 현재 기록에 접수하고 응답 작업을 만든다`() {
        val user = UserId.generate()
        val first = accepted(user, command(Instant.now().minusSeconds(86400 * 10)))
        assertThat(first.record.closedAt).isNull()
        assertThat(first.responseJob).isNotNull()
        assertThat(countUserMessages(user)).isEqualTo(1)
        assertThat(first.message.occurredAt).isBefore(first.record.startAt)
    }

    @Test fun `장소 이동 메시지가 접수 속도 초과로 거절되면 이전 기록의 마감도 롤백한다`() {
        val user = UserId.generate()
        val before = Instant.parse("2026-08-02T05:00:00Z")
        `when`(time.now()).thenReturn(before)
        val first = accepted(user,command(before).copy(timezone="America/Los_Angeles"))
        jdbc.update("UPDATE conversation.conversation_usage SET request_window=?,request_count=60 WHERE user_id=?",
            Timestamp.from(before.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MINUTES)),user.value)
        `when`(time.now()).thenReturn(before.plusSeconds(60))

        assertThatThrownBy { accepted(user,command(before.plusSeconds(60))) }
            .isInstanceOf(com.moeum.conversation.domain.ConversationRateExceededException::class.java)
        val current = query.list(user,null,50).single()
        assertThat(current.id).isEqualTo(first.record.id)
        assertThat(current.closedAt).isNull()
        assertThat(current.closeAt).isEqualTo(first.record.closeAt)
        assertThat(current.timezone).isEqualTo(first.record.timezone)
        assertThat(countUserMessages(user)).isEqualTo(1)
        assertThat(count("conversation.response_jobs",user)).isEqualTo(1)
    }

    @Test fun `마감 후 도착한 과거 발화는 다음 기록에 들어가고 이전 입력은 불변이다`() {
        val user = UserId.generate()
        val first = accepted(user)
        close(first.record.id.value)
        val before = records.readJournalInput(user, first.record.id)
        val next = accepted(user, command(first.message.occurredAt.minusSeconds(86400)))
        assertThat(next.record.id).isNotEqualTo(first.record.id)
        assertThat(next.responseJob).isNotNull()
        assertThat(records.readJournalInput(user, first.record.id)).isEqualTo(before)
        assertThat(before.messages.map { it.content }).containsExactly("원본")
        assertThat(countUserMessages(user)).isEqualTo(2)
    }

    @Test fun `동시 중복 요청은 메시지 원본 버전 quota 작업을 각각 한번만 만든다`() {
        val user = UserId.generate()
        val command = command()
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(8).use { pool ->
            val results = (1..8).map { pool.submit<SaveMessageResult> { start.await(); accepted(user, command) } }
            start.countDown()
            assertThat(results.map { it.get(10, TimeUnit.SECONDS).message.id }.distinct()).hasSize(1)
        }
        assertThat(countUserMessages(user)).isEqualTo(1)
        assertThat(count("conversation.response_jobs", user)).isEqualTo(1)
        assertThat(jdbc.queryForObject<Long>("SELECT source_revision FROM conversation.record_days WHERE user_id=?",
            user.value)).isEqualTo(1)
        assertThat(jdbc.queryForObject<Int>("SELECT request_count FROM conversation.conversation_usage WHERE user_id=?",
            user.value)).isEqualTo(1)
    }

    @Test fun `전달 확인 전에 재전달되어도 생성 작업은 하나이고 이전 버전 확인은 실패한다`() {
        val user = UserId.generate()
        val first = accepted(user)
        close(first.record.id.value)
        val delivery = generateSequence { records.claimDelivery() }.first { it.recordDayId == first.record.id }
        assertThat(registration.register(delivery)).isTrue()
        jdbc.update("UPDATE conversation.record_days SET delivery_lease_expires_at=now()-interval '1 second' WHERE id=?", first.record.id.value)
        val second = generateSequence { records.claimDelivery() }.first { it.recordDayId == first.record.id }
        assertThat(second.version).isGreaterThan(delivery.version)
        assertThat(registration.register(second)).isFalse()
        assertThat(records.acknowledge(delivery)).isFalse()
        assertThat(records.acknowledge(second)).isTrue()
        assertThat(count("journal.generation_jobs", user)).isEqualTo(1)
    }

    @Test fun `활동이 없으면 조회와 마감 검사만으로 빈 기록과 일기는 생기지 않는다`() {
        val user = UserId.generate()
        assertThat(query.list(user, null, 50)).isEmpty()
        assertThat(query.closeTime(user)).isEqualTo(LocalTime.of(2, 0))
        assertThat(count("conversation.record_days", user)).isZero()
        assertThat(count("journal.generation_jobs", user)).isZero()
    }

    @Test fun `미래 5분 초과 입력은 거부한다`() {
        assertThatThrownBy { save.save(UserId.generate(), command(Instant.now().plusSeconds(301))) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }
}
