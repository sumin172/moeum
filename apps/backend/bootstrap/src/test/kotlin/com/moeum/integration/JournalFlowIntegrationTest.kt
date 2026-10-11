package com.moeum.integration

import com.moeum.conversation.interfaces.SaveMessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.identity.application.command.GrantComplimentarySubscriptionService
import com.moeum.identity.domain.model.Plan
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.integration.support.IntegrationEventRecorder
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.RecordDeliveryWorker
import com.moeum.journal.application.publicapi.events.JournalConfirmedV1
import com.moeum.journal.interfaces.dto.ConfirmJournalRequest
import com.moeum.journal.interfaces.dto.EditJournalRequest
import com.moeum.journal.interfaces.dto.JournalDayResponse
import com.moeum.journal.interfaces.dto.JournalResponse
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
import java.time.Duration
import java.time.Instant
import java.util.*

/** 마감 전달·생성·권한·수정 충돌·동결 후 폐기를 실제 API/DB로 검증한다. */
class JournalFlowIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var deliveryWorker: RecordDeliveryWorker
    @Autowired lateinit var generationExecutor: GenerationExecutor
    @Autowired lateinit var grantSubscription: GrantComplimentarySubscriptionService
    @Autowired lateinit var jwtProvider: JwtProvider
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var eventRecorder: IntegrationEventRecorder

    private fun sendMessage(jwt: String, content: String): SaveMessageResponse =
        restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages",HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(),content,Instant.now().minus(Duration.ofDays(1)),"Asia/Seoul"),authHeaders(jwt))).body!!

    private fun journalDay(jwt: String, day: UUID): JournalDayResponse =
        restTemplate.exchange<JournalDayResponse>("/api/journals/$day", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt))).body!!

    @Test
    fun `닫힌 기록으로 생성된 일기는 구독자만 수정하고 늦은 원본은 일기를 갱신하지 않는다`() {
        val jwt = issueJwt()
        val userId = jwtProvider.parse(jwt).userId
        val saved = sendMessage(jwt, "오늘 한강을 걸었다")
        val day = saved.recordDayId

        deliveryWorker.runBatch()
        repeat(100) {
            if (journalDay(jwt,day).generation?.status != "COMPLETED") generationExecutor.executeNext()
        }

        val generated = journalDay(jwt, day)
        assertThat(generated.generation!!.status).isEqualTo("COMPLETED")
        val journal = generated.journal!!
        assertThat(journal.title).isEqualTo("테스트 일기")
        assertThat(journal.lifecycleStatus).isEqualTo("DRAFT")
        assertThat(journal.editable).isFalse()

        // free는 수정 불가
        val freeEdit = restTemplate.exchange<ErrorResponse>(
            "/api/journals/$day", HttpMethod.PUT, HttpEntity(EditJournalRequest("제목", "본문", journal.version), authHeaders(jwt)),
        )
        assertThat(freeEdit.statusCode).isEqualTo(HttpStatus.FORBIDDEN)

        // 확정 + 이벤트
        val confirmed = restTemplate.exchange<JournalResponse>(
            "/api/journals/$day/confirm", HttpMethod.POST, HttpEntity(ConfirmJournalRequest(journal.version), authHeaders(jwt)),
        ).body!!
        assertThat(confirmed.lifecycleStatus).isEqualTo("CONFIRMED")
        val event = eventRecorder.events.filterIsInstance<JournalConfirmedV1>().single { it.userId == userId.value }
        assertThat(event.displayDate).isEqualTo(journal.displayDate)

        // 구독을 지급하면 수정 가능. 확정 전의 낡은 version으로는 충돌
        grantSubscription.grant(userId, Plan.PREMIUM)
        val stale = restTemplate.exchange<ErrorResponse>(
            "/api/journals/$day", HttpMethod.PUT, HttpEntity(EditJournalRequest("제목", "본문", journal.version), authHeaders(jwt)),
        )
        assertThat(stale.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val edited = restTemplate.exchange<JournalResponse>(
            "/api/journals/$day", HttpMethod.PUT,
            HttpEntity(EditJournalRequest("한강 산책", "바람이 좋았다", confirmed.version), authHeaders(jwt)),
        ).body!!
        assertThat(edited.lifecycleStatus).isEqualTo("DRAFT")
        assertThat(edited.revision).isEqualTo(2)
        assertThat(edited.editable).isTrue()

        // 재확정 후 늦은 원본도 동결된 일기를 갱신하지 않는다
        restTemplate.exchange<JournalResponse>(
            "/api/journals/$day/confirm", HttpMethod.POST, HttpEntity(ConfirmJournalRequest(edited.version), authHeaders(jwt)),
        )
        val late = restTemplate.exchange<SaveMessageResponse>("/api/conversations/messages",HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(),"늦은 원본",Instant.now().minus(Duration.ofDays(1)),"Asia/Seoul",day),authHeaders(jwt))).body!!
        assertThat(late.status).isEqualTo("DISCARDED_INPUT_FROZEN")
        assertThat(journalDay(jwt,day).journal!!.lifecycleStatus).isEqualTo("CONFIRMED")
    }

    @Test
    fun `생성할 일기가 없는 하루에 재생성을 요청하면 404를 반환한다`() {
        val response = restTemplate.exchange<ErrorResponse>(
            "/api/journals/${UUID.randomUUID()}/generation-attempts", HttpMethod.POST, HttpEntity<Void>(authHeaders(issueJwt())),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.code).isEqualTo("JOURNAL_NOT_FOUND")
    }
}
