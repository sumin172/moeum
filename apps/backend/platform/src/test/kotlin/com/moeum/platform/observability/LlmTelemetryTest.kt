package com.moeum.platform.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LlmTelemetryTest {
    private fun event() = LlmCallEvent("chat", "google", "model", "v1", UUID.randomUUID(), 42, true, null, 10, 5)

    @Test
    fun `수집기가 느려도 호출 스레드는 대기하지 않고 큐 포화만 집계한다`() {
        val registry = SimpleMeterRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val telemetry = BufferedLlmTelemetry({
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }, registry, TelemetryProperties(1))
        telemetry.start()
        try {
            telemetry.record(event())
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            telemetry.record(event())
            telemetry.record(event())
            assertThat(registry.get("moeum.telemetry.dropped").tag("reason", "buffer_full").counter().count()).isEqualTo(1.0)
            assertThat(registry.get("moeum.telemetry.buffer.size").gauge().value()).isEqualTo(1.0)
        } finally { release.countDown(); telemetry.close() }
        assertThat(registry.get("moeum.llm.duration").timer().count()).isEqualTo(2)
        assertThat(registry.get("moeum.llm.input.tokens").counter().count()).isEqualTo(20.0)
    }

    @Test
    fun `수집기 실패는 다음 이벤트 수집을 막지 않고 유실 수를 남긴다`() {
        val registry = SimpleMeterRegistry()
        var attempts = 0
        val telemetry = BufferedLlmTelemetry({
            attempts++
            if (attempts == 1) throw IllegalStateException("SECRET_BODY")
        }, registry, TelemetryProperties())
        telemetry.record(event())
        telemetry.record(event())
        telemetry.start()
        telemetry.close()
        assertThat(attempts).isEqualTo(2)
        assertThat(registry.get("moeum.telemetry.dropped").tag("reason", "sink_failure").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `작업 문맥은 수집 스레드까지 전달하고 이전 MDC는 복구한다`() {
        val logger = LoggerFactory.getLogger("com.moeum.telemetry") as Logger
        val collector = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(collector)
        val previous = MDC.getCopyOfContextMap()
        MDC.put("traceId", "previous")
        try {
            val job = UUID.randomUUID()
            val day = UUID.randomUUID()
            val registry = SimpleMeterRegistry()
            val telemetry = BufferedLlmTelemetry(StructuredLlmEventSink(), registry, TelemetryProperties())
            WorkTrace.run(job, day, 2) { telemetry.record(event()) }
            assertThat(MDC.get("traceId")).isEqualTo("previous")
            telemetry.start()
            telemetry.close()
            val fields = collector.list.single().keyValuePairs.associate { it.key to it.value }
            assertThat(fields["jobId"]).isEqualTo(job.toString())
            assertThat(fields["recordDayId"]).isEqualTo(day.toString())
            assertThat(fields["attempt"]).isEqualTo("2")
            assertThat(fields["traceId"]).isEqualTo(job.toString())
            assertThat(fields["generationId"]).isNotNull()
            assertThat(registry.meters.flatMap { it.id.tags }.map { it.key }).doesNotContain("jobId", "traceId", "generationId", "userId")
        } finally {
            logger.detachAppender(collector)
            collector.stop()
            MDC.clear()
            previous?.let(MDC::setContextMap)
        }
    }

    @Test
    fun `수집기가 멈춰도 종료 대기는 제한되고 남은 큐는 유실로 집계한다`() {
        val registry = SimpleMeterRegistry()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val telemetry = BufferedLlmTelemetry({
            entered.countDown()
            var done = false
            while (!done) {
                try { done = release.await(5, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { /* interrupt를 무시하는 전송기를 재현한다. */ }
            }
        }, registry, TelemetryProperties(shutdownTimeout = Duration.ofMillis(10)))
        telemetry.start()
        try {
            telemetry.record(event())
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            telemetry.record(event())
            val before = System.nanoTime()
            telemetry.close()
            assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(1))
            assertThat(registry.get("moeum.telemetry.dropped").tag("reason", "closed").counter().count()).isEqualTo(1.0)
        } finally { release.countDown(); telemetry.close() }
    }

    @Test
    fun `닫힌 수집기에 전달한 이벤트는 유실로 집계한다`() {
        val registry = SimpleMeterRegistry()
        val telemetry = BufferedLlmTelemetry({}, registry, TelemetryProperties(shutdownTimeout = Duration.ZERO))
        telemetry.close()
        telemetry.record(event())
        assertThat(registry.get("moeum.telemetry.dropped").tag("reason", "closed").counter().count()).isEqualTo(1.0)
    }
}
