package com.moeum.integration

import com.moeum.conversation.interfaces.dto.ResponseStateResponse
import com.moeum.conversation.interfaces.dto.SaveMessageRequest
import com.moeum.conversation.interfaces.dto.RecordConversationResponse
import com.moeum.integration.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.boot.resttestclient.exchange
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import java.time.Instant
import java.util.UUID

/**
 * 기록 단위 AI 응답 작업의 자동 재시도(poller)와 최종 실패 뒤 새 메시지에 의한 재개를 실제 DB·스케줄러·비동기 실행으로 검증한다.
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

    @Test
    fun `일시적으로 실패한 응답은 poller가 자동 재시도해 결국 완료된다`() {
        val jwt = issueJwt()
        val clientMessageId = UUID.randomUUID()
        conversationResponder.failNext(clientMessageId, 2) // 3번까지 시도하므로 세 번째에 성공

        val saved = saveMessage(jwt, "자동 재시도 테스트", clientMessageId)

        val completed = awaitResponse(jwt) { it.status == "COMPLETED" }
        assertThat(completed.response.answeredSourceRevision).isEqualTo(saved.message.sourceSequence)
        assertThat(attempts(saved.recordDayId)).isEqualTo(3)
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
}
