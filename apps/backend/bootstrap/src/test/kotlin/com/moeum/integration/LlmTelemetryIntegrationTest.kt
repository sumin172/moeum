package com.moeum.integration

import com.moeum.conversation.application.command.SaveMessageCommand
import com.moeum.conversation.application.command.SaveMessageService
import com.moeum.conversation.application.publicapi.RecordDayOperations
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.RecordDeliveryWorker
import com.moeum.kernel.UserId
import com.moeum.platform.llm.*
import com.moeum.platform.observability.WorkTrace
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.*

@ExtendWith(OutputCaptureExtension::class)
class LlmTelemetryIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var llm: LlmClient
    @Autowired lateinit var registry: MeterRegistry
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var mapper: JsonMapper
    @Autowired lateinit var save: SaveMessageService
    @Autowired lateinit var deliveryWorker: RecordDeliveryWorker
    @Autowired lateinit var executor: GenerationExecutor
    @Autowired lateinit var records: RecordDayOperations

    private fun request(content: String) = LlmRequest("integration-test", "PRIVATE_SYSTEM_PROMPT",
        listOf(LlmMessage(LlmRole.USER, content)), "v-test")

    private fun awaitEvent(output: CapturedOutput, job: UUID, event: String): tools.jackson.databind.JsonNode {
        repeat(200) {
            output.all.lineSequence().filter { it.startsWith("{") }.forEach { line ->
                val node = runCatching { mapper.readTree(line) }.getOrNull()
                if (node?.get("jobId")?.asString() == job.toString() && node.get("event")?.asString() == event) return node
            }
            Thread.sleep(10)
        }
        throw AssertionError("관측 이벤트를 수집하지 못했습니다: job=$job, event=$event")
    }

    @Test
    fun `실제 JSON 로그 수집 경로와 메트릭은 작업 생성 ID를 연결하고 본문은 노출하지 않는다`(output: CapturedOutput) {
        val job = UUID.randomUUID()
        val result = WorkTrace.run(job, UUID.randomUUID(), 1) { llm.generate(request("PRIVATE_USER_CONTENT")) }
        val event = awaitEvent(output, job, "llm.call")
        assertThat(event["generationId"].asString()).isEqualTo(result.generationId.toString())
        assertThat(event["traceId"].asString()).isEqualTo(job.toString())
        assertThat(event["inputTokens"].asInt()).isEqualTo(7)
        assertThat(output.all).doesNotContain("PRIVATE_USER_CONTENT", "PRIVATE_SYSTEM_PROMPT")
        assertThat(registry.get("moeum.logging.dropped").functionCounter().count()).isGreaterThanOrEqualTo(0.0)
        assertThat(registry.get("moeum.logging.buffer.size").gauge().value()).isGreaterThanOrEqualTo(0.0)
        assertThat(registry.get("moeum.llm.duration").tag("purpose", "integration-test").tag("outcome", "success").timer().count()).isPositive()
        val names = java.lang.management.ManagementFactory.getPlatformMBeanServer().queryNames(null, null)
            .filter { it.domain == "org.springframework.boot" }
        assertThat(names.any { it.getKeyProperty("name") == "Metrics" }).withFailMessage("JMX endpoints: %s", names).isTrue()
    }

    @Test
    fun `timeout과 429는 원문 없는 분류로 수집되며 예외 cause에 응답 본문이 남지 않는다`(output: CapturedOutput) {
        listOf("timeout" to "TIMEOUT", "429" to "HTTP_429").forEach { (content, code) ->
            val job = UUID.randomUUID()
            val error = catchThrowable { WorkTrace.run(job, UUID.randomUUID(), 2) { llm.generate(request(content)) } }
            assertThat(error).isInstanceOf(LlmException::class.java).hasMessage("LLM 호출 실패").hasNoCause()
            assertThat((error as LlmException).errorCode).isEqualTo(code)
            assertThat(awaitEvent(output, job, "llm.call")["errorCode"].asString()).isEqualTo(code)
        }
        assertThat(output.all).doesNotContain("PRIVATE_BODY_BEARER")
    }

    @Test
    fun `일기 파싱 실패 로그는 성공한 공급자 호출과 같은 생성 ID로 연결된다`(output: CapturedOutput) {
        val user = UserId.generate()
        val saved = save.save(user, SaveMessageCommand(UUID.randomUUID(), "invalid-json", Instant.now().minus(Duration.ofDays(1)), "Asia/Seoul"))
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
        val job = jdbc.queryForObject<UUID>("SELECT id FROM journal.generation_jobs WHERE user_id=?", user.value)!!
        var attempts = 0
        while (jdbc.queryForObject<Int>("SELECT attempt_count FROM journal.generation_jobs WHERE id=?", job)!! == 0 && attempts++ < 100) executor.executeNext()
        val failed = awaitEvent(output, job, "job.failed")
        val call = awaitEvent(output, job, "llm.call")
        assertThat(failed["errorCode"].asString()).isEqualTo("JournalGenerationException")
        assertThat(failed["generationId"].asString()).isEqualTo(call["generationId"].asString())
        assertThat(output.all).doesNotContain("PRIVATE_BODY_BEARER", "PRIVATE_SYSTEM_PROMPT")
        assertThat(jdbc.queryForObject<Int>("SELECT count(*) FROM journal.journals WHERE user_id=?", user.value)).isZero()
    }
}
