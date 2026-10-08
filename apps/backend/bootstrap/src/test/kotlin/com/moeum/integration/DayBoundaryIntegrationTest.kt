package com.moeum.integration

import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.conversation.interfaces.dto.ChangeDayStartTimeRequest
import com.moeum.conversation.interfaces.dto.DayPreferenceResponse
import com.moeum.conversation.interfaces.dto.MessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.conversation.interfaces.dto.TodayConversationResponse
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.platform.security.jwt.JwtProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * 하루 경계 설정 API, 메시지의 dayDate 저장, Journal이 쓰는 ConversationActivityQuery를 실제 DB로 검증한다.
 */
class DayBoundaryIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var conversationActivityQuery: ConversationActivityQuery

    @Autowired
    lateinit var jwtProvider: JwtProvider

    @Test
    fun `하루 시작 시각은 기본값으로 조회되고, 변경하면 다음 하루부터 적용되는 예약으로 저장된다`() {
        val jwt = issueJwt()

        val initial = restTemplate.exchange<String>(
            "/api/conversations/day-preference", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        )
        assertThat(initial.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(initial.body).contains("\"dayStartTime\":\"02:00:00\"")

        val changed = restTemplate.exchange<DayPreferenceResponse>(
            "/api/conversations/day-preference/day-start-time",
            HttpMethod.PUT,
            HttpEntity(ChangeDayStartTimeRequest(dayStartTime = LocalTime.of(4, 0), timezone = "Asia/Seoul"), authHeaders(jwt)),
        )
        assertThat(changed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(changed.body!!.dayStartTime).isEqualTo(LocalTime.of(2, 0))
        assertThat(changed.body!!.pendingDayStartTime).isEqualTo(LocalTime.of(4, 0))

        val reloaded = restTemplate.exchange<DayPreferenceResponse>(
            "/api/conversations/day-preference", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        )
        assertThat(reloaded.body).isEqualTo(changed.body)
    }

    @Test
    fun `저장된 메시지는 오늘 하루로 조회되고, Journal용 활동 조회에서 그 하루로 잡힌다`() {
        val jwt = issueJwt()
        val userId = jwtProvider.parse(jwt).userId
        val before = Instant.now()
        restTemplate.exchange<MessageResponse>(
            "/api/conversations/messages",
            HttpMethod.POST,
            HttpEntity(SaveMessageRequest(UUID.randomUUID(), "하루 경계 테스트", Instant.now(), "Asia/Seoul"), authHeaders(jwt)),
        )

        val today = restTemplate.exchange<TodayConversationResponse>(
            "/api/conversations/today?timezone=Asia/Seoul", HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        ).body!!

        val activeDay = conversationActivityQuery.findActiveDays(before, Instant.now().plus(Duration.ofSeconds(1)))
            .single { it.userId == userId }
        assertThat(activeDay.dayDate).isEqualTo(today.dayDate)
        assertThat(activeDay.dayEnd).isAfter(before)
        assertThat(conversationActivityQuery.findMessages(userId, today.dayDate).map { it.content })
            .contains("하루 경계 테스트")
    }
}
