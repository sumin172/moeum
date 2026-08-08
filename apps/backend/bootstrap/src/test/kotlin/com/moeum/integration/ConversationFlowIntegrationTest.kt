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

        // AI 응답(assistant 메시지)까지 비동기로 도착하는 걸 기다린다 — 유저 메시지 1개 + assistant 메시지 1개가 최종 상태.
        val messages = awaitTodayMessages(jwt, expectedSize = 2)

        val userMessages = messages.filter { it.role == "USER" }
        assertThat(userMessages).hasSize(1)
        assertThat(userMessages.single().id).isEqualTo(savedId)
        assertThat(userMessages.single().content).isEqualTo("오늘 정말 피곤했다")
    }

    @Test
    fun `메시지 저장 후 AI 응답이 비동기로 생성되어 오늘 대화 조회에 나타난다`() {
        val jwt = issueJwt()
        val request = SaveMessageRequest(
            clientMessageId = UUID.randomUUID(),
            content = "오늘 정말 피곤했다",
            occurredAt = Instant.now(),
            timezone = "Asia/Seoul",
        )
        restTemplate.exchange<MessageResponse>(
            "/api/conversations/messages",
            HttpMethod.POST,
            HttpEntity(request, authHeaders(jwt)),
        )

        val messages = awaitTodayMessages(jwt, expectedSize = 2)

        val userMessage = messages.single { it.role == "USER" }
        assertThat(userMessage.responseStatus).isEqualTo("COMPLETED")
        val assistantMessage = messages.single { it.role == "ASSISTANT" }
        assertThat(assistantMessage.content).isEqualTo("테스트 응답입니다.")
    }

    private fun awaitTodayMessages(jwt: String, expectedSize: Int): List<MessageResponse> {
        repeat(20) {
            val response = restTemplate.exchange<TodayConversationResponse>(
                "/api/conversations/today?timezone=Asia/Seoul",
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders(jwt)),
            )
            val messages = response.body?.messages.orEmpty()
            if (messages.size >= expectedSize) return messages
            Thread.sleep(200)
        }
        throw AssertionError("AI 응답이 시간 내에 도착하지 않았습니다")
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
