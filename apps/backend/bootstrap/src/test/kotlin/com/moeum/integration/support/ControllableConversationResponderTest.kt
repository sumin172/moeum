package com.moeum.integration.support

import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ControllableConversationResponderTest {
    private val responder = ControllableConversationResponder()
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    private fun message(user: UserId = UserId.generate(), record: RecordDayId = RecordDayId.generate(), sequence: Long = 1) =
        Message.userMessage(MessageId.generate(), user, "기록", now, record, sequence, UUID.randomUUID(), now)

    @Test
    fun `다른 사용자나 과거 문맥에만 있는 대상은 실패 횟수를 소비하지 않는다`() {
        val target = message()
        val later = message(target.userId, target.recordDayId, 2)
        responder.failNext(target.clientMessageId!!, 2)

        assertThat(responder.respond(emptyList(), listOf(message())).content).isNotBlank()
        assertThat(responder.respond(listOf(target), listOf(later)).content).isNotBlank()
        repeat(2) {
            assertThatThrownBy { responder.respond(emptyList(), listOf(target, later)) }.isInstanceOf(LlmException::class.java)
        }
        assertThat(responder.respond(emptyList(), listOf(target)).content).isNotBlank()
    }

    @Test
    fun `동시 호출도 대상 메시지의 지정 횟수만 실패한다`() {
        val target = message()
        val unrelated = message()
        responder.failNext(target.clientMessageId!!, 10)

        Executors.newFixedThreadPool(8).use { pool ->
            val results = (1..30).map {
                pool.submit<Pair<Boolean, Boolean>> {
                    val unrelatedSucceeded = runCatching { responder.respond(emptyList(), listOf(unrelated)) }.isSuccess
                    val targetFailed = runCatching { responder.respond(emptyList(), listOf(target)) }.exceptionOrNull() is LlmException
                    unrelatedSucceeded to targetFailed
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(results.all { it.first }).isTrue()
            assertThat(results.count { it.second }).isEqualTo(10)
        }
    }
}
