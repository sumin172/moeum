package com.moeum.capacity

import com.moeum.MoeumApplication
import com.moeum.platform.llm.*
import com.moeum.platform.llm.provider.*
import com.moeum.platform.observability.WorkTrace
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import tools.jackson.databind.json.JsonMapper
import java.lang.management.ManagementFactory
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class CapacityControl(val conversationDelayMs: Long = 100, val journalDelayMs: Long = 200, val failure: String = "none", val failureAttempts: Int = 0)
data class ProviderStart(val jobId: String, val startedAt: String)

/** 테스트 classpath에서만 실행한다. 이 제어 HTTP 서버는 운영 jar에 포함되지 않는다. */
object CapacityRoleMain {
    @JvmStatic fun main(args: Array<String>) {
        val role = args.first()
        val mapper = JsonMapper.builder().build()
        val fake = CapacityProvider()
        val context = SpringApplicationBuilder(MoeumApplication::class.java)
            .profiles("local", role)
            .web(if (role == "api") WebApplicationType.SERVLET else WebApplicationType.NONE)
            .initializers({ it.beanFactory.registerSingleton("capacityProvider", fake) })
            .run(*args.drop(1).toTypedArray())
        val registry = context.getBean<MeterRegistry>()
        val pool = context.getBean<HikariDataSource>()
        val control = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        val controlThreads = Executors.newVirtualThreadPerTaskExecutor()
        control.executor = controlThreads
        control.createContext("/metrics") { exchange ->
            val runtime = Runtime.getRuntime()
            val metrics = mapOf(
                "pid" to ProcessHandle.current().pid(), "role" to role,
                "heapUsedBytes" to (runtime.totalMemory() - runtime.freeMemory()),
                "heapMaxBytes" to runtime.maxMemory(),
                "processCpuNs" to ((ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime),
                "gcCount" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) },
                "gcMs" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) },
                "dbActive" to pool.hikariPoolMXBean.activeConnections,
                "dbTotal" to pool.hikariPoolMXBean.totalConnections,
                "dbPending" to pool.hikariPoolMXBean.threadsAwaitingConnection,
                "configuredClaimBatchSize" to if(role=="conversation-worker") context.getBean<com.moeum.conversation.infrastructure.config.ResponseJobProperties>().claimBatchSize else 0,
                "providerActive" to fake.active.get(), "providerPeak" to fake.peak.get(),
                "providerCalls" to fake.calls.get(), "providerFailures" to fake.failures.get(),
                "sampleCount" to fake.sampleCount(), "sampleSeen" to fake.seen.get(),
                "telemetryDropped" to registry.find("moeum.telemetry.dropped").counters().sumOf { it.count() },
                "consoleDropped" to (registry.find("moeum.logging.dropped").functionCounter()?.count() ?: 0.0),
                "dbAcquire" to registry.find("hikaricp.connections.acquire").timers().map {
                    mapOf("count" to it.count(), "totalSeconds" to it.totalTime(java.util.concurrent.TimeUnit.SECONDS), "maxSeconds" to it.max(java.util.concurrent.TimeUnit.SECONDS))
                },
            )
            respond(exchange, mapper.writeValueAsBytes(metrics))
        }
        control.createContext("/clean") { exchange ->
            val deleted = when (role) {
                "conversation-worker" -> context.getBean<com.moeum.conversation.infrastructure.scheduling.TerminalResponseJobCleaner>().cleanBatch()
                "journal-worker" -> context.getBean<com.moeum.journal.infrastructure.scheduling.TerminalGenerationJobCleaner>().cleanBatch()
                else -> 0
            }
            respond(exchange, mapper.writeValueAsBytes(mapOf("deleted" to deleted)))
        }
        control.createContext("/samples") { exchange -> respond(exchange, mapper.writeValueAsBytes(fake.samples())) }
        if (role=="journal-worker") control.createContext("/crash-delivery") { exchange ->
            val records=context.getBean<com.moeum.conversation.application.publicapi.RecordDayOperations>()
            check(records.closeNext()) { "마감할 시험 기록이 없습니다" }
            val delivery=checkNotNull(records.claimDelivery())
            val after=exchange.requestURI.rawQuery=="after=true"
            if(after) context.getBean<com.moeum.journal.application.command.RegisterRecordGenerationService>().register(delivery)
            Files.writeString(Path.of(System.getProperty("capacity.readyFile")+".delivery-crash.json"),
                mapper.writeValueAsString(mapOf("recordDayId" to delivery.recordDayId.value.toString(),
                    "version" to delivery.version,"afterRegistration" to after,"crashedAt" to Instant.now().toString())))
            Runtime.getRuntime().halt(91)
        }
        control.createContext("/control") { exchange ->
            fake.control = mapper.readValue(exchange.requestBody.readNBytes(16384), CapacityControl::class.java)
            respond(exchange, "{}".toByteArray())
        }
        control.start()
        Files.writeString(Path.of(System.getProperty("capacity.readyFile")), mapper.writeValueAsString(mapOf(
            "pid" to ProcessHandle.current().pid(), "controlPort" to control.address.port,
            "apiPort" to context.environment.getProperty("local.server.port"), "role" to role,
        )))
        Runtime.getRuntime().addShutdownHook(Thread {
            control.stop(0)
            controlThreads.close()
            context.close()
        })
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, bytes: ByteArray) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}

private class CapacityProvider : LlmProvider {
    override val name = "capacity-fake"
    override val vendor = "capacity"
    @Volatile var control = CapacityControl(
        System.getProperty("capacity.conversationDelayMs","100").toLong(),
        System.getProperty("capacity.journalDelayMs","200").toLong(),
    )
    val calls = AtomicLong()
    val failures = AtomicLong()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val seen = AtomicLong()
    private val starts = mutableListOf<ProviderStart>()

    override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
        val settings = control
        val concurrent = active.incrementAndGet()
        peak.accumulateAndGet(concurrent, ::maxOf)
        calls.incrementAndGet()
        val trace = WorkTrace.snapshot()
        val attempt = trace["attempt"]?.toInt() ?: 0
        val chars = request.messages.sumOf { it.content.length }
        if (request.purpose == "conversation-response" && attempt == 1) {
            val sample = ProviderStart(trace.getValue("jobId"), Instant.now().toString())
            synchronized(starts) {
                val count = seen.incrementAndGet()
                if (starts.size < 50000) starts += sample
                else {
                    val replacement = ThreadLocalRandom.current().nextLong(count)
                    if (replacement < starts.size) starts[replacement.toInt()] = sample
                }
            }
        }
        try {
            Thread.sleep(if (request.purpose == "journal-generation") settings.journalDelayMs else settings.conversationDelayMs)
            if (attempt <= settings.failureAttempts && settings.failure != "none") {
                failures.incrementAndGet()
                when (settings.failure) {
                    "timeout" -> throw java.net.SocketTimeoutException("capacity timeout")
                    "429" -> throw org.springframework.web.client.HttpClientErrorException.create(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "capacity", org.springframework.http.HttpHeaders(), byteArrayOf(), null)
                    "5xx" -> throw org.springframework.web.client.HttpServerErrorException.create(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "capacity", org.springframework.http.HttpHeaders(), byteArrayOf(), null)
                }
            }
            val text = if (request.purpose == "journal-generation") """{"title":"synthetic journal","body":"synthetic generated body"}""" else "synthetic response"
            return LlmProviderResult(text, "synthetic-v1", (chars / 3).coerceAtLeast(1), 10)
        } finally { active.decrementAndGet() }
    }
    fun samples(): List<ProviderStart> = synchronized(starts) { starts.toList() }
    fun sampleCount(): Int = synchronized(starts) { starts.size }
}
