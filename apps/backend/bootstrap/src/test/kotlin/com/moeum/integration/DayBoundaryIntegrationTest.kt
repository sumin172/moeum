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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DayBoundaryIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var query: RecordQueryService
    @Autowired lateinit var registration: RegisterRecordGenerationService
    @Autowired lateinit var jdbc: JdbcTemplate

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

    @Test fun `설정은 즉시 저장하지만 이미 열린 기록의 마감 스냅샷은 유지한다`() {
        val jwt = issueJwt()
        val initial = restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences", HttpMethod.GET,
            HttpEntity<Void>(authHeaders(jwt))).body!!
        assertThat(initial.closeTime).isEqualTo(LocalTime.of(2, 0))
        val saved = restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages", HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(), "스냅샷", Instant.now(), "Asia/Seoul"), authHeaders(jwt))).body!!
        restTemplate.exchange<CloseTimeRequest>("/api/conversations/preferences", HttpMethod.PUT,
            HttpEntity(CloseTimeRequest(LocalTime.of(4, 0)), authHeaders(jwt)))
        assertThat(jdbc.queryForObject<LocalTime>("SELECT close_time FROM conversation.record_days WHERE id=?",
            saved.recordDayId)).isEqualTo(LocalTime.of(2, 0))
    }

    @Test fun `오래된 발화 시각이어도 현재 기록에 접수하고 응답 작업을 만든다`() {
        val user = UserId.generate()
        val first = accepted(user, command(Instant.now().minusSeconds(86400 * 10)))
        assertThat(first.record.closedAt).isNull()
        assertThat(first.responseJob).isNotNull()
        assertThat(countUserMessages(user)).isEqualTo(1)
        assertThat(first.message.occurredAt).isBefore(first.record.startAt)
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
            val results = (1..8).map { pool.submit<SaveMessageResult.Accepted> { start.await(); accepted(user, command) } }
            start.countDown()
            assertThat(results.map { it.get(10, TimeUnit.SECONDS).message.id }.distinct()).hasSize(1)
        }
        assertThat(countUserMessages(user)).isEqualTo(1)
        assertThat(count("conversation.response_jobs", user)).isEqualTo(1)
        assertThat(jdbc.queryForObject<Long>("SELECT source_revision FROM conversation.record_days WHERE user_id=?",
            user.value)).isEqualTo(1)
        assertThat(jdbc.queryForObject<Int>("SELECT message_count FROM conversation.conversation_usage WHERE user_id=?",
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
