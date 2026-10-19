
package com.moeum.integration

import com.moeum.conversation.application.command.ConversationQuotaService
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.interfaces.SaveMessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.integration.support.insertPremiumSubscription
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.RecordDeliveryWorker
import com.moeum.journal.interfaces.dto.*
import com.moeum.platform.security.jwt.JwtProvider
import com.moeum.platform.web.ErrorResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 마감 전달·즉시 보관·서버 권한·수정 충돌·닫힌 입력의 무료 복구를 실제 API/DB로 검증한다. */
class JournalFlowIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var deliveryWorker: RecordDeliveryWorker
    @Autowired lateinit var generationExecutor: GenerationExecutor
    @Autowired lateinit var jwtProvider: JwtProvider
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var quota: ConversationQuotaService
    @Autowired lateinit var records: RecordDayOperations

    private fun sendMessage(jwt: String, content: String): SaveMessageResponse {
        val saved = restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages",HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(),content,Instant.now(),"Asia/Seoul"),authHeaders(jwt))).body!!
        jdbc.update("UPDATE conversation.record_days SET close_at=? WHERE id=?",
            java.sql.Timestamp.from(Instant.now().plusMillis(2)), saved.recordDayId)
        Thread.sleep(5)
        repeat(100) {
            records.closeNext()
            if (jdbc.queryForObject<Boolean>("SELECT closed_at IS NOT NULL FROM conversation.record_days WHERE id=?", saved.recordDayId) == true) return saved
        }
        error("기록이 마감되지 않았습니다: ${saved.recordDayId}")
    }
    private fun journalDay(jwt: String, day: UUID) =
        restTemplate.exchange<JournalDayResponse>("/api/journals/$day",HttpMethod.GET,HttpEntity<Void>(authHeaders(jwt))).body!!
    private fun awaitGeneration(jwt: String, day: UUID, status: String): JournalDayResponse {
        repeat(100) {
            generationExecutor.executeNext()
            val current = journalDay(jwt,day)
            if (current.generation?.status == status) return current
            Thread.sleep(25)
        }
        throw AssertionError("일기 작업이 기대 상태에 도달하지 않았습니다: expected=$status, actual=${journalDay(jwt,day).generation?.status}")
    }

    @Test fun `생성 즉시 읽을 수 있고 구독자 수정은 별도 확정 없이 보관된다`() {
        val jwt = issueJwt()
        val user = jwtProvider.parse(jwt).userId
        val saved = sendMessage(jwt,"오늘 한강을 걸었다")
        val day = saved.recordDayId
        deliveryWorker.runBatch()
        val journal = awaitGeneration(jwt,day,"COMPLETED").journal!!
        assertThat(journal.title).isEqualTo("테스트 일기")
        assertThat(journal.editable).isFalse()
        assertThat(journal.version).isZero()
        val freeEdit = restTemplate.exchange<ErrorResponse>("/api/journals/$day",HttpMethod.PUT,
            HttpEntity(EditJournalRequest("제목","본문",journal.version),authHeaders(jwt)))
        assertThat(freeEdit.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        insertPremiumSubscription(jdbc,user)
        val edited = restTemplate.exchange<JournalResponse>("/api/journals/$day",HttpMethod.PUT,
            HttpEntity(EditJournalRequest("한강 산책","바람이 좋았다",journal.version),authHeaders(jwt))).body!!
        assertThat(edited.version).isEqualTo(journal.version+1)
        assertThat(edited.editable).isTrue()
        val stale = restTemplate.exchange<ErrorResponse>("/api/journals/$day",HttpMethod.PUT,
            HttpEntity(EditJournalRequest("낡은 제목","낡은 본문",journal.version),authHeaders(jwt)))
        assertThat(stale.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val late = restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages",HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(),"다음 기록의 대화",Instant.now().minus(Duration.ofDays(1)),"Asia/Seoul"),authHeaders(jwt))).body!!
        assertThat(late.message.content).isEqualTo("다음 기록의 대화")
        assertThat(late.recordDayId).isNotEqualTo(day)
        assertThat(journalDay(jwt,day).journal!!.body).isEqualTo("바람이 좋았다")
        assertThat(quota.current(user).used).isEqualTo(2)
    }

    @Test fun `무료 사용자 실패 복구는 같은 마감 입력을 사용하고 대화 횟수를 추가 차감하지 않는다`() {
        val jwt = issueJwt()
        val user = jwtProvider.parse(jwt).userId
        val day = sendMessage(jwt,"서버 실패 복구").recordDayId
        fakeLlmProvider.failNextJournal(day,2)
        deliveryWorker.runBatch()
        assertThat(awaitGeneration(jwt,day,"FAILED").journal).isNull()
        assertThat(jdbc.queryForObject<Long>("SELECT source_revision FROM conversation.record_days WHERE id=?",
            day)).isEqualTo(1)
        val before = quota.current(user).used
        val retry = restTemplate.exchange<GenerationStatusResponse>("/api/journals/$day/generation-attempts",HttpMethod.POST,HttpEntity<Void>(authHeaders(jwt)))
        assertThat(retry.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        val complete = awaitGeneration(jwt,day,"COMPLETED")
        assertThat(complete.journal!!.editable).isFalse()
        assertThat(quota.current(user).used).isEqualTo(before)
        assertThat(fakeLlmProvider.inputsFor(day)).hasSize(3)
        assertThat(fakeLlmProvider.inputsFor(day).distinct()).hasSize(1)
        val again = restTemplate.exchange<ErrorResponse>("/api/journals/$day/generation-attempts",HttpMethod.POST,HttpEntity<Void>(authHeaders(jwt)))
        assertThat(again.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test fun `없는 기록의 생성 재시도는 404이고 기존 확정 API는 제공하지 않는다`() {
        val jwt = issueJwt()
        val missing = restTemplate.exchange<ErrorResponse>("/api/journals/${UUID.randomUUID()}/generation-attempts",HttpMethod.POST,HttpEntity<Void>(authHeaders(jwt)))
        assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val removed = restTemplate.exchange<String>("/api/journals/${UUID.randomUUID()}/confirm",HttpMethod.POST,HttpEntity(mapOf("version" to 0),authHeaders(jwt)))
        assertThat(removed.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
