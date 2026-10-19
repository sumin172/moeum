package com.moeum.integration

import com.moeum.conversation.application.command.SaveMessageCommand
import com.moeum.conversation.application.command.SaveMessageResult
import com.moeum.conversation.application.command.SaveMessageService
import com.moeum.conversation.application.publicapi.JournalInput
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.RecordDayNotFoundException
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClosedJournalInputIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @MockitoSpyBean lateinit var messages: MessageRepository

    private fun send(user: UserId, content: String) = save.save(user,
        SaveMessageCommand(UUID.randomUUID(), content, Instant.now(), "Asia/Seoul"))

    private fun assistant(first: SaveMessageResult.Accepted, source: Long, content: String,
                          occurredAt: Instant = Instant.now()) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            messages.append(Message.assistantMessage(MessageId.generate(), first.message.userId, content,
                occurredAt, first.record.id, source))
        }
    }

    private fun close(first: SaveMessageResult.Accepted) {
        val boundary = Timestamp.from(Instant.now())
        jdbc.update("UPDATE conversation.record_days SET close_at=?,closed_at=? WHERE id=?",
            boundary, boundary, first.record.id.value)
    }

    @Test fun `마감된 입력 본문을 읽는 동안 다음 기록 저장은 대기하지 않고 AI 문맥은 고정된다`() {
        val user = UserId.generate()
        val first = send(user, "오늘 칭찬받았어")
        assistant(first, 1, "팀장님한테?", first.message.occurredAt)
        send(user, "아니, 동료한테")
        close(first)
        assistant(first, 2, "마감 후 도착한 AI 응답")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closedAt = jdbc.queryForObject<Timestamp>("SELECT closed_at FROM conversation.record_days WHERE id=?",
            first.record.id.value)!!.toInstant()
        doAnswer { invocation ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            invocation.callRealMethod()
        }.`when`(messages).findJournalInput(user, first.record.id, 2, closedAt)

        Executors.newFixedThreadPool(2).use { pool ->
            val input = pool.submit<JournalInput> { records.readJournalInput(user, first.record.id) }
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                val next = pool.submit<SaveMessageResult.Accepted> { send(user, "다음 기록") }.get(5, TimeUnit.SECONDS)
                assertThat(next.record.id).isNotEqualTo(first.record.id)
            } finally { release.countDown() }
            assertThat(input.get(5, TimeUnit.SECONDS).messages.map { it.content })
                .containsExactly("오늘 칭찬받았어", "팀장님한테?", "아니, 동료한테")
            assertThat(records.readJournalInput(user, first.record.id).messages.map { it.content })
                .containsExactly("오늘 칭찬받았어", "팀장님한테?", "아니, 동료한테")
        }
        assertThatThrownBy { records.readJournalInput(UserId.generate(), first.record.id) }
            .isInstanceOf(RecordDayNotFoundException::class.java)
    }

    @Test fun `본문 조회 실패 후 재시도도 같은 마지막 사용자 메시지까지 읽는다`() {
        val user = UserId.generate()
        val first = send(user, "오늘 칭찬받았어")
        close(first)
        val failOnce = AtomicBoolean(true)
        val closedAt = jdbc.queryForObject<Timestamp>("SELECT closed_at FROM conversation.record_days WHERE id=?",
            first.record.id.value)!!.toInstant()
        doAnswer { invocation ->
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            if (failOnce.getAndSet(false)) error("synthetic read failure")
            invocation.callRealMethod()
        }.`when`(messages).findJournalInput(user, first.record.id, 1, closedAt)
        assertThatThrownBy { records.readJournalInput(user, first.record.id) }
            .isInstanceOf(IllegalStateException::class.java)
        assistant(first, 1, "뒤늦게 완료된 질문")
        assertThat(records.readJournalInput(user, first.record.id).messages.map { it.content })
            .containsExactly("오늘 칭찬받았어")
    }
}
