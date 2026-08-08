package com.moeum.integration

import com.moeum.conversation.interfaces.dto.MessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.conversation.interfaces.dto.TodayConversationResponse
import com.moeum.identity.interfaces.dto.GoogleLoginRequest
import com.moeum.identity.interfaces.dto.GoogleLoginResponse
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.platform.web.ErrorResponse
import com.moeum.platform.web.PlatformErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.resttestclient.exchange
import org.springframework.boot.resttestclient.postForEntity
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID

/**
 * Google 실서버 검증만 [AbstractIntegrationTest.FakeGoogleVerifierConfig]로 우회하고,
 * 로그인 → JWT 발급 → 필터 체인 인증 → 메시지 저장(Flyway/JPA 실물) → 조회까지 실제 경로로 검증한다.
 */
class ConversationFlowIntegrationTest : AbstractIntegrationTest() {

    private fun issueJwt(): String {
        val response = restTemplate.postForEntity<GoogleLoginResponse>(
            "/api/auth/google",
            GoogleLoginRequest(idToken = "dummy-${UUID.randomUUID()}"),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!.jwt
    }

    private fun authHeaders(jwt: String): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(jwt)
            contentType = MediaType.APPLICATION_JSON
        }

    @Test
    fun `로그인부터 메시지 저장, 오늘 대화 조회까지 실제 DB로 검증한다`() {
        val jwt = issueJwt()
        val request = SaveMessageRequest(
            clientMessageId = UUID.randomUUID(),
            content = "오늘 정말 피곤했다",
            occurredAt = Instant.now(),
            timezone = "Asia/Seoul",
        )

        val saveResponse = restTemplate.exchange<MessageResponse>(
            "/api/conversations/messages",
            HttpMethod.POST,
            HttpEntity(request, authHeaders(jwt)),
        )
        assertThat(saveResponse.statusCode).isEqualTo(HttpStatus.OK)
        val savedId = saveResponse.body!!.id

        // 같은 clientMessageId 재전송 -> 중복 저장 대신 기존 메시지를 그대로 반환해야 한다
        val retryResponse = restTemplate.exchange<MessageResponse>(
            "/api/conversations/messages",
            HttpMethod.POST,
            HttpEntity(request, authHeaders(jwt)),
        )
        assertThat(retryResponse.body!!.id).isEqualTo(savedId)

        val todayResponse = restTemplate.exchange<TodayConversationResponse>(
            "/api/conversations/today?timezone=Asia/Seoul",
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(jwt)),
        )
        assertThat(todayResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(todayResponse.body!!.messages).hasSize(1)
        assertThat(todayResponse.body!!.messages.single().id).isEqualTo(savedId)
        assertThat(todayResponse.body!!.messages.single().content).isEqualTo("오늘 정말 피곤했다")
    }

    @Test
    fun `인증 없이 호출하면 401과 플랫폼 공통 에러 포맷을 반환한다`() {
        val response = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/today?timezone=Asia/Seoul",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders()),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!.code).isEqualTo(PlatformErrorCode.UNAUTHORIZED.code)
    }
}
