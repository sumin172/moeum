package com.moeum.integration

import com.moeum.conversation.interfaces.dto.MessageResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.conversation.interfaces.dto.RecordConversationResponse
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.platform.web.ErrorResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/**
 * AI 응답 작업의 자동 재시도(poller)와 사용자 재시도 요청을 실제 DB·스케줄러·비동기 실행으로 검증한다.
 * backoff/poll 주기는 AbstractIntegrationTest에서 짧게 줄여 둔다.
 */
class ResponseRetryIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun attempts(messageId: UUID): Int? = jdbc.queryForObject<Int>(
        "SELECT attempt_count FROM conversation.response_jobs WHERE user_message_id=?", messageId)

    private fun saveMessage(jwt: String, content: String, clientMessageId: UUID = UUID.randomUUID()): MessageResponse =
        restTemplate.exchange<com.moeum.conversation.interfaces.SaveMessageResponse>(
            "/api/conversations/messages",
            HttpMethod.POST,
            HttpEntity(SaveMessageRequest(clientMessageId, content, Instant.now(), "Asia/Seoul"), authHeaders(jwt)),
        ).body!!.message

    private fun awaitUserMessage(jwt: String, id: UUID, condition: (MessageResponse) -> Boolean): MessageResponse {
        repeat(50) {
            val messages = restTemplate.exchange<RecordConversationResponse>(
                recordUrl(jwt), HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
            ).body!!.messages
            messages.find { it.id == id }?.takeIf(condition)?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("메시지 상태가 기대한 대로 바뀌지 않았습니다: id=$id")
    }

    @Test
    fun `일시적으로 실패한 응답은 poller가 자동 재시도해 결국 완료된다`() {
        val jwt = issueJwt()
        val clientMessageId = UUID.randomUUID()
        conversationResponder.failNext(clientMessageId, 2) // 3번까지 시도하므로 세 번째에 성공

        val saved = saveMessage(jwt, "자동 재시도 테스트", clientMessageId)

        val completed = awaitUserMessage(jwt, saved.id) { it.responseStatus == "COMPLETED" }
        assertThat(completed.retryable).isFalse()
        assertThat(attempts(saved.id)).isEqualTo(3)
    }

    @Test
    fun `재시도를 모두 실패한 응답은 재시도 가능으로 표시되고, 사용자가 다시 요청하면 응답이 생성된다`() {
        val jwt = issueJwt()
        val clientMessageId = UUID.randomUUID()
        conversationResponder.failNext(clientMessageId, 3)

        val saved = saveMessage(jwt, "사용자 재시도 테스트", clientMessageId)
        val failed = awaitUserMessage(jwt, saved.id) { it.responseStatus == "FAILED" }
        assertThat(failed.responseFailureReason).isEqualTo("GENERATION_FAILED")
        assertThat(failed.retryable).isTrue()
        assertThat(attempts(saved.id)).isEqualTo(3)

        val retry = restTemplate.exchange<MessageResponse>(
            "/api/conversations/messages/${saved.id}/response-attempts",
            HttpMethod.POST,
            HttpEntity<Void>(authHeaders(jwt)),
        )
        assertThat(retry.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        awaitUserMessage(jwt, saved.id) { it.responseStatus == "COMPLETED" }
        assertThat(attempts(saved.id)).isEqualTo(4)
        val assistantReplies = restTemplate.exchange<RecordConversationResponse>(
            recordUrl(jwt), HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
        ).body!!.messages.filter { it.role == "ASSISTANT" }
        assertThat(assistantReplies).hasSize(1)
    }

    @Test
    fun `이미 응답이 생성된 메시지에 다시 요청하면 409를 반환한다`() {
        val jwt = issueJwt()
        val saved = saveMessage(jwt, "완료 후 재요청")
        awaitUserMessage(jwt, saved.id) { it.responseStatus == "COMPLETED" }

        val retry = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/messages/${saved.id}/response-attempts",
            HttpMethod.POST,
            HttpEntity<Void>(authHeaders(jwt)),
        )

        assertThat(retry.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(retry.body!!.code).isEqualTo("CONVERSATION_RESPONSE_ALREADY_COMPLETED")
    }

    @Test
    fun `다른 사용자의 메시지에 재시도를 요청하면 404를 반환한다`() {
        val saved = saveMessage(issueJwt(), "남의 메시지")

        val retry = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/messages/${saved.id}/response-attempts",
            HttpMethod.POST,
            HttpEntity<Void>(authHeaders(issueJwt())),
        )

        assertThat(retry.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
