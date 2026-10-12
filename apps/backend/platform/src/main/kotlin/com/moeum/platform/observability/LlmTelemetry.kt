package com.moeum.platform.observability

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class LlmCallEvent(
    val purpose: String,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val generationId: UUID,
    val latencyMs: Long,
    val succeeded: Boolean,
    val errorCode: String?,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val finishReason: String? = null,
    val context: Map<String, String> = WorkTrace.snapshot(),
)

fun interface LlmTelemetry {
    fun record(event: LlmCallEvent)
}

@ConfigurationProperties("moeum.telemetry")
data class TelemetryProperties(
    val bufferCapacity: Int = 1024,
    val shutdownTimeout: Duration = Duration.ofSeconds(2),
) {
    init {
        require(bufferCapacity in 1..65536)
        require(!shutdownTimeout.isNegative && shutdownTimeout <= Duration.ofSeconds(10))
    }
}

fun interface LlmEventSink {
    fun emit(event: LlmCallEvent)
}

@Component
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
class StructuredLlmEventSink : LlmEventSink {
    private val log = LoggerFactory.getLogger("com.moeum.telemetry")
    override fun emit(event: LlmCallEvent) {
        val builder = log.atInfo()
            .addKeyValue("event", "llm.call")
            .addKeyValue("purpose", event.purpose)
            .addKeyValue("provider", event.provider)
            .addKeyValue("model", event.model)
            .addKeyValue("promptVersion", event.promptVersion)
            .addKeyValue("generationId", event.generationId.toString())
            .addKeyValue("latencyMs", event.latencyMs)
            .addKeyValue("succeeded", event.succeeded)
            .addKeyValue("errorCode", event.errorCode)
            .addKeyValue("finishReason", event.finishReason)
            .addKeyValue("inputTokens", event.inputTokens)
            .addKeyValue("outputTokens", event.outputTokens)
            .addKeyValue("cachedInputTokens", event.cachedInputTokens)
        event.context.filterKeys { it != "generationId" }.forEach { (key, value) -> builder.addKeyValue(key, value) }
        builder.log("LLM call")
    }
}

/** 수집기의 느린 전송·실패를 업무 스레드에서 격리한다. 포화 시 진단 이벤트만 유실한다. */
@Component
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
class BufferedLlmTelemetry(
    private val sink: LlmEventSink,
    private val registry: MeterRegistry,
    private val properties: TelemetryProperties,
) : LlmTelemetry {
    private val queue = ArrayBlockingQueue<LlmCallEvent>(properties.bufferCapacity)
    private val dropped = registry.counter("moeum.telemetry.dropped", "reason", "buffer_full")
    private val rejected = registry.counter("moeum.telemetry.dropped", "reason", "closed")
    private val sinkFailures = registry.counter("moeum.telemetry.dropped", "reason", "sink_failure")
    private val metricFailures = registry.counter("moeum.telemetry.metric.failures")
    @Volatile private var running = true
    private val consumer = Thread.ofPlatform().daemon().name("llm-telemetry").unstarted { consume() }

    init {
        registry.gauge("moeum.telemetry.buffer.size", queue) { it.size.toDouble() }
    }

    @PostConstruct
    fun start() = consumer.start()

    @Synchronized
    override fun record(event: LlmCallEvent) {
        if (!running) { rejected.increment(); return }
        if (!queue.offer(event)) dropped.increment()
    }

    private fun consume() {
        while (running || queue.isNotEmpty()) {
            val event = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            if (event != null) {
                runCatching {
                    // ID·사용자·모델·오류 문자열은 메트릭 label에 넣지 않는다.
                    val tags = arrayOf("purpose", event.purpose, "provider", event.provider, "outcome", if (event.succeeded) "success" else "failure")
                    registry.timer("moeum.llm.duration", *tags).record(event.latencyMs, TimeUnit.MILLISECONDS)
                    registry.counter("moeum.llm.input.tokens", *tags).increment(event.inputTokens.toDouble())
                    registry.counter("moeum.llm.output.tokens", *tags).increment(event.outputTokens.toDouble())
                    registry.counter("moeum.llm.cached.input.tokens", *tags).increment(event.cachedInputTokens.toDouble())
                }.onFailure { metricFailures.increment() }
                runCatching { sink.emit(event) }.onFailure { sinkFailures.increment() }
            }
        }
    }

    @PreDestroy
    fun close() {
        synchronized(this) { running = false }
        if (consumer.isAlive) consumer.join(properties.shutdownTimeout.toMillis().coerceAtLeast(1))
        if (consumer.isAlive) consumer.interrupt()
        val abandoned = queue.size
        queue.clear()
        rejected.increment(abandoned.toDouble())
    }
}
