package com.moeum.integration

import com.moeum.conversation.application.command.SaveMessageCommand
import com.moeum.conversation.application.command.SaveMessageService
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.RecordDeliveryWorker
import com.moeum.journal.domain.JournalRepository
import com.moeum.kernel.UserId
import com.moeum.platform.observability.LlmCallEvent
import com.moeum.platform.observability.LlmEventSink
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestPropertySource(properties = ["moeum.telemetry.buffer-capacity=1"])
class LlmTelemetryIsolationIntegrationTest : AbstractIntegrationTest() {
    @MockitoBean lateinit var sink: LlmEventSink
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var deliveryWorker: RecordDeliveryWorker
    @Autowired lateinit var executor: GenerationExecutor
    @Autowired lateinit var journals: JournalRepository
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var records: RecordDayOperations
    @Autowired lateinit var jdbc: JdbcTemplate

    @Test fun `관측 전송이 막히고 큐가 포화돼도 원본 접수와 일기 저장은 진행된다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            null
        }.`when`(sink).emit(any<LlmCallEvent>() ?: LlmCallEvent("test", "test", "model", "v1", UUID.randomUUID(), 0, true, null))
        try {
            val created = (1..3).map {
                val user = UserId.generate()
                val saved = save.save(user, SaveMessageCommand(UUID.randomUUID(), "수집기 정체 시험",
                    Instant.now().minus(Duration.ofDays(1)), "Asia/Seoul"))
                jdbc.update("UPDATE conversation.record_days SET close_at=? WHERE id=?",
                    java.sql.Timestamp.from(Instant.now().plusMillis(2)), saved.record.id.value)
                Thread.sleep(5)
                var closeAttempts = 0
                while (closeAttempts++ < 100) {
                    if (jdbc.queryForObject<Boolean>("SELECT closed_at IS NOT NULL FROM conversation.record_days WHERE id=?", saved.record.id.value) == true) break
                    records.closeNext()
                }
                check(jdbc.queryForObject<Boolean>("SELECT closed_at IS NOT NULL FROM conversation.record_days WHERE id=?", saved.record.id.value) == true)
                var deliveryAttempts = 0
                while (deliveryAttempts++ < 100) {
                    if (jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE record_day_id=?", saved.record.id.value)!! > 0) break
                    deliveryWorker.runBatch()
                }
                var attempts = 0
                while (journals.findByUserIdAndRecordDayId(user, saved.record.id) == null && attempts++ < 100) {
                    executor.executeNext()
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
                journals.findByUserIdAndRecordDayId(user, saved.record.id)
            }
            assertThat(created).doesNotContainNull()
            assertThat(registry.get("moeum.telemetry.dropped").tag("reason", "buffer_full").counter().count()).isGreaterThanOrEqualTo(1.0)
        } finally { release.countDown() }
    }
}
