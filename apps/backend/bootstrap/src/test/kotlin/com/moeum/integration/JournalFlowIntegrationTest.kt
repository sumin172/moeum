package com.moeum.integration

import com.moeum.conversation.interfaces.dto.MessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.conversation.interfaces.dto.TodayConversationResponse
import com.moeum.identity.application.command.GrantComplimentarySubscriptionService
import com.moeum.identity.domain.model.Plan
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.integration.support.IntegrationEventRecorder
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.GenerationPlanner
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
import java.time.LocalDate
import java.util.UUID

/**
 * Stage 2 흐름 전체: 대화 → 일기 생성 → 조회 → (free) 수정 거부 → 확정 + JournalConfirmed →
 * 구독 지급 → 수정(낡은 version은 409) → 재확정 → 새 메시지 → OUTDATED.
 * 스케줄러 주기를 기다리지 않도록 Planner/Executor를 직접 부르고, 하루가 끝난 것처럼 작업 시각만 당긴다.
 */
class JournalFlowIntegrationTest : AbstractIntegrationTest() {

    @Autowired lateinit var generationPlanner: GenerationPlanner
    @Autowired lateinit var generationExecutor: GenerationExecutor
    @Autowired lateinit var grantSubscription: GrantComplimentarySubscriptionService
    @Autowired lateinit var jwtProvider: JwtProvider
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var eventRecorder: IntegrationEventRecorder

    private fun sendMessage(jwt: String, content: String) {
        restTemplate.exchange<MessageResponse>(
            "/api/conversations/messages", HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(), content, Instant.now(), "Asia/Seoul"), authHeaders(jwt)),
        )
    }

    private fun today(jwt: String): LocalDate =
        restTemplate.exchange<TodayConversationResponse>(
            "/api/conversations/today?timezone=Asia/Seoul", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        ).body!!.dayDate

    private fun planRecent(since: Instant) = generationPlanner.plan(since, Instant.now().plusSeconds(1), "TEST")

    private fun journalDay(jwt: String, day: LocalDate): JournalDayResponse =
        restTemplate.exchange<JournalDayResponse>("/api/journals/$day", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt))).body!!

    @Test
    fun `대화로 생성된 일기를 조회·확정하고, 구독자만 수정하며, 확정 후 새 메시지가 오면 OUTDATED가 된다`() {
        val jwt = issueJwt()
        val userId = jwtProvider.parse(jwt).userId
        val start = Instant.now()
        sendMessage(jwt, "오늘 한강을 걸었다")
        val day = today(jwt)

        // 하루가 끝났다고 보고 생성
        planRecent(start)
        jdbcTemplate.update(
            "UPDATE journal.generation_jobs SET next_attempt_at = now() - interval '1 second' WHERE user_id = ?", userId.value,
        )
        generationExecutor.executeDueJobs()

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
        assertThat(event.diaryDate).isEqualTo(day)

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

        // 재확정 후 새 메시지 → OUTDATED
        restTemplate.exchange<JournalResponse>(
            "/api/journals/$day/confirm", HttpMethod.POST, HttpEntity(ConfirmJournalRequest(edited.version), authHeaders(jwt)),
        )
        val beforeNewMessage = Instant.now().minus(Duration.ofSeconds(1))
        sendMessage(jwt, "저녁에 친구도 만났다")
        planRecent(beforeNewMessage)

        assertThat(journalDay(jwt, day).journal!!.lifecycleStatus).isEqualTo("OUTDATED")
    }

    @Test
    fun `생성할 일기가 없는 하루에 재생성을 요청하면 404를 반환한다`() {
        val response = restTemplate.exchange<ErrorResponse>(
            "/api/journals/2020-01-01/generation-attempts", HttpMethod.POST, HttpEntity<Void>(authHeaders(issueJwt())),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!.code).isEqualTo("JOURNAL_NOT_FOUND")
    }
}
