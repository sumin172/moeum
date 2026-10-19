package com.moeum.integration

import com.moeum.conversation.application.command.SaveMessageCommand
import com.moeum.conversation.application.command.SaveMessageService
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@TestPropertySource(properties = [
    "spring.datasource.hikari.maximum-pool-size=1",
    "spring.datasource.hikari.minimum-idle=1",
    "moeum.conversation.response-job.initial-delay=PT1H",
])
class JournalInputConnectionIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `단일 DB 커넥션으로 닫힌 기록의 JDBC와 JPA 입력 조회를 마친다`() {
        val user = UserId.generate()
        val saved = save.save(user, SaveMessageCommand(UUID.randomUUID(), "오늘 기억", Instant.now(), "Asia/Seoul"))
        val boundary = Timestamp.from(Instant.now())
        jdbc.update("UPDATE conversation.record_days SET close_at=?,closed_at=? WHERE id=?",
            boundary, boundary, saved.record.id.value)

        val input = records.readJournalInput(user, saved.record.id)

        assertThat(input.messages.map { it.content }).containsExactly("오늘 기억")
    }
}
