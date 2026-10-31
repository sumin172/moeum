package com.moeum.integration

import com.moeum.conversation.interfaces.dto.ResponseStateResponse
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
 * 기록 단위 AI 응답 작업의 자동 재시도(poller)와 사용자 재시도 요청을 실제 DB·스케줄러·비동기 실행으로 검증한다.
 * backoff/poll 주기는 AbstractIntegrationTest에서 짧게 줄여 둔다.
 */
class ResponseRetryIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun attempts(recordDayId: UUID): Int? = jdbc.queryForObject<Int>(
        "SELECT attempt_count FROM conversation.response_jobs WHERE record_day_id=?", recordDayId)

    private fun saveMessage(jwt: String, content: String, clientMessageId: UUID = UUID.randomUUID()) =
        restTemplate.exchange<com.moeum.conversation.interfaces.SaveMessageResponse>(
            "/api/conversations/messages",
            HttpMethod.POST,
            HttpEntity(SaveMessageRequest(clientMessageId, content, Instant.now(), "Asia/Seoul"), authHeaders(jwt)),
        ).body!!

    private fun record(jwt: String) = restTemplate.exchange<RecordConversationResponse>(
        recordUrl(jwt), HttpMethod.GET, HttpEntity<Void>(authHeaders(jwt)),
    ).body!!

    private fun awaitResponse(jwt: String, condition: (ResponseStateResponse) -> Boolean): RecordConversationResponse {
        repeat(50) {
            record(jwt).takeIf { condition(it.response) }?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("응답 상태가 기대한 대로 바뀌지 않았습니다")
    }

    // 실패 시 저장한 재개 가능 시각(테스트 backoff 200ms) 전에는 429이므로 허용될 때까지 다시 요청한다.
    private fun retryWhenAllowed(jwt: String, recordDayId: UUID): HttpStatus {
        repeat(20) {
            val status = restTemplate.exchange<String>("/api/conversations/records/$recordDayId/response-attempts",
                HttpMethod.POST, HttpEntity<Void>(authHeaders(jwt))).statusCode
            if (status != HttpStatus.TOO_MANY_REQUESTS) return HttpStatus.valueOf(status.value())
            Thread.sleep(100)
        }
        throw AssertionError("재개 가능 시각 이후에도 재시도가 허용되지 않았습니다")
    }

    @Test
    fun `일시적으로 실패한 응답은 poller가 자동 재시도해 결국 완료된다`() {
        val jwt = issueJwt()
        val clientMessageId = UUID.randomUUID()
        conversationResponder.failNext(clientMessageId, 2) // 3번까지 시도하므로 세 번째에 성공

        val saved = saveMessage(jwt, "자동 재시도 테스트", clientMessageId)

        val completed = awaitResponse(jwt) { it.status == "COMPLETED" }
        assertThat(completed.response.retryable).isFalse()
        assertThat(completed.response.answeredSourceRevision).isEqualTo(saved.message.sourceSequence)
        assertThat(attempts(saved.recordDayId)).isEqualTo(3)
    }

    @Test
    fun `재시도를 모두 실패한 응답은 재시도 가능으로 표시되고, 사용자가 다시 요청하면 같은 범위로 응답이 생성된다`() {
        val jwt = issueJwt()
        val clientMessageId = UUID.randomUUID()
        conversationResponder.failNext(clientMessageId, 3)

        val saved = saveMessage(jwt, "사용자 재시도 테스트", clientMessageId)
        val failed = awaitResponse(jwt) { it.status == "FAILED" }
        assertThat(failed.response.retryable).isTrue()
        assertThat(failed.response.answeredSourceRevision).isZero()
        assertThat(attempts(saved.recordDayId)).isEqualTo(3)

        assertThat(retryWhenAllowed(jwt, saved.recordDayId)).isEqualTo(HttpStatus.ACCEPTED)

        val completed = awaitResponse(jwt) { it.status == "COMPLETED" }
        // 사용자 재요청은 시도 횟수를 초기화하지 않는다
        assertThat(attempts(saved.recordDayId)).isEqualTo(4)
        assertThat(completed.messages.filter { it.role == "ASSISTANT" }).hasSize(1)
    }

    @Test
    fun `최종 실패 뒤 새 메시지는 실패한 입력과 함께 답하는 새 실행 주기를 연다`() {
        val jwt = issueJwt()
        val clientMessageId = UUID.randomUUID()
        conversationResponder.failNext(clientMessageId, 3)
        val first = saveMessage(jwt, "실패할 입력", clientMessageId)
        awaitResponse(jwt) { it.status == "FAILED" }

        val next = saveMessage(jwt, "다음 입력")
        assertThat(next.responseStatus).isEqualTo("PENDING")

        val completed = awaitResponse(jwt) { it.status == "COMPLETED" }
        val assistant = completed.messages.single { it.role == "ASSISTANT" }
        assertThat(assistant.sourceSequence).isEqualTo(next.message.sourceSequence)
        assertThat(completed.response.answeredSourceRevision).isEqualTo(2)
        assertThat(attempts(first.recordDayId)).isEqualTo(1)
    }

    @Test
    fun `재시도할 실패 응답이 없는 기록에 다시 요청하면 409를 반환한다`() {
        val jwt = issueJwt()
        val saved = saveMessage(jwt, "완료 후 재요청")
        awaitResponse(jwt) { it.status == "COMPLETED" }

        val retry = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/records/${saved.recordDayId}/response-attempts",
            HttpMethod.POST,
            HttpEntity<Void>(authHeaders(jwt)),
        )

        assertThat(retry.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(retry.body!!.code).isEqualTo("CONVERSATION_RESPONSE_ALREADY_COMPLETED")
    }

    @Test
    fun `다른 사용자의 기록에 재시도를 요청하면 404를 반환한다`() {
        val saved = saveMessage(issueJwt(), "남의 메시지")

        val retry = restTemplate.exchange<ErrorResponse>(
            "/api/conversations/records/${saved.recordDayId}/response-attempts",
            HttpMethod.POST,
            HttpEntity<Void>(authHeaders(issueJwt())),
        )

        assertThat(retry.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
