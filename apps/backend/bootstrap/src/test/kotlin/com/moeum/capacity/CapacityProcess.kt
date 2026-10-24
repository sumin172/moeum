package com.moeum.capacity

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 자신의 자식 PID만 종료한다. 기존 개발 서버·DB·volume은 사용하지 않는다. */
class CapacityProcess(
    val role: String,
    private val index: Int,
    private val settings: CapacitySettings,
    private val jdbcUrl: String,
    private val directory: Path,
    private val pauseDelivery: Boolean = false,
    private val retentionEnabled: Boolean = false,
) : AutoCloseable {
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val ready = directory.resolve("$role-$index-ready.json")
    val logFile: Path = directory.resolve("$role-$index.log")
    private lateinit var process: Process
    private lateinit var address: String
    var apiPort: Int = 0; private set
    val pid: Long get() = process.pid()

    fun start() {
        Files.deleteIfExists(ready)
        val pool = when (role) { "api" -> settings.apiPool; "conversation-worker" -> settings.conversationPool; else -> settings.journalPool }
        val slots = if (role == "journal-worker") settings.journalSlots else settings.conversationSlots
        val executable=if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", executable)
        val arguments = listOf(
            "-Xms128m", "-Xmx512m", "-Dcapacity.readyFile=$ready",
            "-Dcapacity.conversationDelayMs=${settings.conversationDelayMs}", "-Dcapacity.journalDelayMs=${settings.journalDelayMs}",
            "-cp", System.getProperty("capacity.childClasspath"),
            CapacityRoleMain::class.java.name, role,
            "--spring.datasource.url=$jdbcUrl", "--spring.datasource.username=test", "--spring.datasource.password=test",
            "--spring.datasource.hikari.maximum-pool-size=$pool", "--spring.datasource.hikari.minimum-idle=1",
            "--spring.datasource.hikari.pool-name=$role-$index", "--server.port=0",
            "--spring.datasource.hikari.data-source-properties.ApplicationName=capacity-$role-$index",
            "--moeum.jwt.secret=${CapacitySettings.JWT_SECRET}",
            "--moeum.llm.routes.conversation-response.provider=capacity-fake",
            "--moeum.llm.routes.journal-generation.provider=capacity-fake",
            "--moeum.llm.max-concurrent-calls.capacity-fake=$slots",
            "--moeum.runtime.conversation-concurrency=${settings.conversationSlots}",
            "--moeum.runtime.journal-concurrency=${settings.journalSlots}",
            "--moeum.runtime.shutdown-timeout=2s",
            "--moeum.conversation.response-job.poll-interval=PT0.05S",
            "--moeum.conversation.response-job.claim-batch-size=${settings.conversationBatchSize}",
            "--moeum.journal.generation-job.poll-interval=PT0.1S",
            "--moeum.conversation.response-job.backoff=1s,2s",
            "--moeum.conversation.response-job.rate-limit-backoff=2s",
            "--moeum.journal.generation-job.backoff=1s,2s,4s,8s",
            "--moeum.journal.generation-job.rate-limit-backoff=2s",
            "--moeum.journal.delivery.initial-delay=${if (pauseDelivery) "PT24H" else "PT0S"}",
            // 전달 경계 시험에서는 등록 직후 generation 선점이 먼저 일어나지 않게 격리한다.
            "--moeum.journal.generation-job.initial-delay=${if (pauseDelivery) "PT24H" else "PT0S"}",
            "--moeum.jobs.retention.initial-delay=${if (retentionEnabled) "PT1M" else "PT24H"}",
            "--moeum.jobs.retention.interval=${if (retentionEnabled) "PT10S" else "PT24H"}",
            "--logging.level.org.springframework=WARN",
            "--logging.level.org.hibernate=WARN",
            "--logging.level.com.moeum=INFO",
        )
        val argFile = directory.resolve("$role-$index-java.args")
        Files.writeString(argFile, arguments.joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
        process = ProcessBuilder(java.toString(), "@$argFile").redirectErrorStream(true).redirectOutput(logFile.toFile()).start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline && process.isAlive) {
            if (Files.exists(ready)) {
                val state = runCatching { mapper.readTree(Files.readString(ready)) }.getOrNull()
                if (state != null) {
                    address = "http://127.0.0.1:" + state["controlPort"].asInt()
                    apiPort = state["apiPort"]?.asInt() ?: 0
                    update(CapacityControl(settings.conversationDelayMs.toLong(), settings.journalDelayMs.toLong()))
                    return
                }
            }
            Thread.sleep(100)
        }
        error("역할 기동 실패: role=$role, pid=$pid, log=$logFile")
    }

    fun metrics(): JsonNode = get("/metrics")
    fun samples(): JsonNode = get("/samples")
    fun clean(): Int = get("/clean")["deleted"].asInt()
    fun crashDelivery(afterRegistration: Boolean): JsonNode {
        runCatching { get("/crash-delivery?after=$afterRegistration") }
        check(process.waitFor(15, TimeUnit.SECONDS)) { "전달 경계 강제 종료 실패: pid=$pid" }
        check(process.exitValue()==91) { "전달 경계 시험 종료 코드가 아닙니다: ${process.exitValue()}" }
        return mapper.readTree(Files.readString(Path.of("$ready.delivery-crash.json")))
    }
    fun update(control: CapacityControl) {
        val request = HttpRequest.newBuilder(URI("$address/control")).timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(control))).build()
        check(http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200)
    }

    private fun get(path: String): JsonNode {
        val response = http.send(HttpRequest.newBuilder(URI(address + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200)
        return mapper.readTree(response.body())
    }

    fun kill() {
        if (::process.isInitialized && process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(15, TimeUnit.SECONDS)) { "자식 JVM 종료 실패: pid=$pid" }
        }
    }
    override fun close() {
        kill()
        http.close()
    }
}
