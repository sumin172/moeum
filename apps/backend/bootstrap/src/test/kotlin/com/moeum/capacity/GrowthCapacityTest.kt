package com.moeum.capacity

import com.moeum.kernel.UserId
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.nio.file.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@Tag("capacity")
class GrowthCapacityTest {
    private val mapper = JsonMapper.builder().build()
    private val settings = CapacitySettings()
    // 성능 목표는 목적이 목표 확인인 시험에서만 판정한다. benchmark P0·P2는 측정, failure는 복구 확인이 목적이다.
    // 부하를 만들지 못한 도구 실패(주입기 드롭)는 목적과 관계없이 실패다.
    private val goalsEnforced = settings.scenario == "sustained" || (settings.scenario == "benchmark" && settings.profile == "P1")
    private val directory = Path.of("build", "capacity", Instant.now().toString().replace(":","-") + "-" + UUID.randomUUID().toString().take(8)).toAbsolutePath()
    private val processes = mutableListOf<CapacityProcess>()
    private val report = linkedMapOf<String, Any?>()

    @Test fun `real HTTP separate JVM and PostgreSQL capacity experiments`() {
        // 데워지지 않은 JVM의 지연이 첫 구간 판정을 실패시키지 않게 한다
        require(!goalsEnforced || settings.warmupSeconds >= 30) {
            "목표를 판정하는 시험(benchmark P1·sustained)은 capacity.warmupSeconds가 30 이상이어야 합니다"
        }
        Files.createDirectories(directory)
        println("CAPACITY settings=" + mapper.writeValueAsString(settings))
        println("CAPACITY artifacts=$directory")
        report["startedAt"] = Instant.now().toString()
        report["state"] = "measurement-running"
        report["settings"] = settings
        report["performanceGoalsEnforced"] = goalsEnforced
        report["java"] = System.getProperty("java.version")
        report["os"] = System.getProperty("os.name")+" "+System.getProperty("os.version")
        report["hostAvailableProcessors"] = Runtime.getRuntime().availableProcessors()
        report["driverMaxHeapBytes"] = Runtime.getRuntime().maxMemory()
        report["databaseLimits"] = mapOf("cpu" to 4, "memoryBytes" to 4L*1024*1024*1024, "sharedBuffers" to "256MB", "maxConnections" to 100)
        report["measurementScope"] = "synthetic provider, local Windows JVMs and disposable PostgreSQL; not an external provider or 360M-source-row approval"
        snapshotClasspath()
        report["activePhase"] = "database-start"
        writeReport()
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withUsername("test").withPassword("test")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "shared_buffers=256MB",
                "-c", "max_connections=100", "-c", "track_io_timing=on", "-c", "track_wal_io_timing=on")
            .withCreateContainerCmdModifier { command ->
                command.hostConfig!!.withMemory(4L*1024*1024*1024).withNanoCPUs(4000000000L)
            }
        try {
            postgres.start()
            report["postgresImage"] = postgres.dockerImageName
            report["containerDisk"] = postgres.execInContainer("df", "-k", "/var/lib/postgresql/data").stdout.trim()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl;username="test";password="test";maximumPoolSize=2;minimumIdle=1;poolName="capacity-driver"
            }).use { pool ->
                val jdbc = JdbcTemplate(pool)
                val api = start("api", 0, postgres.jdbcUrl)
                jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
                val fixture = CapacityFixtures(jdbc)
                report["activePhase"] = "history-seed"
                writeReport()
                val seedStart = System.nanoTime()
                fixture.history(settings.historyRecords)
                report["seedSeconds"] = (System.nanoTime()-seedStart)/1000000000.0
                report["initialDatabase"] = databaseSize(jdbc)
                val conversations = mutableListOf(start("conversation-worker", 0, postgres.jdbcUrl))
                CapacityLoad(fixture,jdbc).use { load ->
                    val phases = mutableListOf<Map<String,Any?>>()
                    val preparations = mutableListOf<Map<String,Any?>>()
                    report["phases"] = phases
                    report["cohortPreparations"] = preparations
                    fun preparedUsers(name: String): List<UserId>? {
                        if (settings.scenario != "sustained") return null
                        val count = ((settings.rps.toLong() * phaseDuration(name) + 16) / 17).toInt().coerceAtLeast(10)
                        val users = fixture.users(count)
                        val requests = ConcurrentLinkedQueue<CapacityRequest>()
                        report["activePhase"] = "$name-cohort-preparation"
                        writeReport()
                        val started = System.nanoTime()
                        val preparationRps = minOf(settings.rps, 100)
                        val injection = load.prepareOpenRecords(api.apiPort, preparationRps, users, requests)
                        val preparation = mutableMapOf<String, Any?>("phase" to name, "users" to count,
                            "rps" to preparationRps, "injection" to injection,
                            "statuses" to requests.groupingBy { it.status }.eachCount())
                        preparations += preparation
                        writeReport()
                        check(injection["generatorRejected"] == 0L && requests.size == count &&
                            requests.all { it.status == 200 && it.messageId != null && it.jobId != null }) {
                            "기존 기록 사용자 준비 접수 실패: phase=$name, injection=$injection, " +
                                "statuses=${requests.groupingBy { it.status }.eachCount()}"
                        }
                        check(drain(load, requests, settings.drainSeconds)) { "기존 기록 사용자 준비 응답 미완료: phase=$name" }
                        load.verifyStored(requests)
                        preparation["responsesDrained"] = true
                        preparation["elapsedSeconds"] = (System.nanoTime() - started) / 1000000000.0
                        writeReport()
                        return users
                    }
                    if (settings.warmupSeconds>0) {
                        report["activePhase"] = "warmup"
                        writeReport()
                        println("CAPACITY warmup seconds=${settings.warmupSeconds}")
                        val warm = ConcurrentLinkedQueue<CapacityRequest>()
                        load.run(api.apiPort,settings.rps,settings.warmupSeconds,warm)
                        check(drain(load,warm,settings.drainSeconds)) { "워밍업 backlog가 남아 측정 구간을 시작할 수 없습니다" }
                    }
                    fun enforceConversationGoals(summary:Map<String,Any?>) {
                        if(goalsEnforced) check(summary["apiGoalPassed"]==true && summary["responseWaitingGoalPassed"]==true) {
                            "대화 기준선 목표 미달: phase=${summary["name"]}. 마감 부하를 추가하기 전에 원인 수정·재시험이 필요합니다"
                        }
                    }
                    phases += phase("conversation-baseline", api, conversations, null, jdbc, load, emptyList(), false, postgres,
                        preparedUsers("conversation-baseline"))
                    writeReport()
                    enforceConversationGoals(phases.last())
                    if (settings.conversationReplicas>1) {
                        for (i in 1 until settings.conversationReplicas) conversations += start("conversation-worker",i,postgres.jdbcUrl)
                        phases += phase("conversation-scaled",api,conversations,null,jdbc,load,emptyList(),false,postgres,
                            preparedUsers("conversation-scaled"))
                        writeReport()
                        enforceConversationGoals(phases.last())
                    }
                    val mixedUsers = preparedUsers("journal-plus-conversation")
                    val burst = fixture.journalBurst(settings.journalBurst)
                    val journal = start("journal-worker",0,postgres.jdbcUrl)
                    phases += phase("journal-plus-conversation",api,conversations,journal,jdbc,load,burst,settings.historyRecords>0,postgres,mixedUsers)
                    writeReport()
                    val base = phases.last { it["name"] != "journal-plus-conversation" }
                    val mixed = phases.last()
                    val base95 = (base["apiLatency"] as Map<*,*>)["p95Ms"] as? Double
                    val mixed95 = (mixed["apiLatency"] as Map<*,*>)["p95Ms"] as? Double
                    report["journalApiP95IncreaseFraction"] = if(base95 != null && mixed95 != null) mixed95/base95-1 else null
                    report["journalComparisonValid"] = base["responsesDrained"] == true
                    writeReport()
                    if(goalsEnforced) {
                        check(mixed["apiGoalPassed"]==true && mixed["responseWaitingGoalPassed"]==true) { "혼합 부하 API·대화 대기 목표 미달" }
                        check(burst.isEmpty() || (mixed["journalCompletion"] as Map<*,*>)["goalPassed"]==true) { "일기 생성 기한 목표 미달" }
                        check(burst.size<2 || mixed["journalPolicyDuringLoad"]!=null || mixed["journalPolicyAfterDrain"]!=null) {
                            "구독 수정·마감 후 대화 정책 미검증"
                        }
                    }
                    if(settings.scenario=="failure") {
                        report["activePhase"] = "provider-faults"
                        writeReport()
                        report["providerFaults"] = faults(api,conversations,jdbc,load)
                        report["activePhase"] = "journal-jvm-recovery"
                        writeReport()
                        report["journalJvmRecovery"] = journalRecovery(journal,postgres.jdbcUrl,fixture,jdbc,api,conversations,load)
                        report["activePhase"] = "delivery-boundary-recovery"
                        writeReport()
                        report["deliveryBoundaryRecovery"] = deliveryRecovery(postgres.jdbcUrl,fixture,jdbc)
                        writeReport()
                    }
                    report["finalDatabase"] = databaseSize(jdbc)
                    report["functionalIntegrity"] = jdbc.queryForMap("""
                        SELECT (SELECT count(*) FROM (
                            SELECT record_day_id,source_sequence FROM conversation.messages WHERE role='ASSISTANT'
                            GROUP BY record_day_id,source_sequence HAVING count(*)>1) d) AS duplicate_responses,
                            (SELECT count(*) FROM (
                                SELECT user_id,record_day_id FROM journal.journals
                                GROUP BY user_id,record_day_id HAVING count(*)>1) d) AS duplicate_journals
                    """.trimIndent())
                    check((report["functionalIntegrity"] as Map<*,*>).values.all { (it as Number).toLong()==0L })
                    report["completedAt"] = Instant.now().toString()
                    report["state"] = "measurement-completed"
                    report["activePhase"] = null
                }
            }
        } catch (error: Throwable) {
            report["state"]="measurement-failed"
            report["failureType"]=error.javaClass.simpleName
            throw error
        } finally {
            writeReport()
            processes.asReversed().forEach { runCatching { it.close() } }
            postgres.close()
        }
    }

    private fun snapshotClasspath() {
        val original=Files.readString(Path.of(System.getProperty("capacity.classpathFile")))
        val entries=original.split(java.io.File.pathSeparator).mapIndexed { index,value ->
            val source=Path.of(value)
            if (!Files.exists(source) || !source.any { it.toString()=="build" }) return@mapIndexed value
            val target=directory.resolve("runtime").resolve(index.toString())
            if(Files.isDirectory(source)) {
                Files.walk(source).use { paths -> paths.forEach { file ->
                    val copy=target.resolve(source.relativize(file))
                    if(Files.isDirectory(file))Files.createDirectories(copy)
                    else Files.copy(file,copy)
                } }
            } else {
                Files.createDirectories(target)
                Files.copy(source,target.resolve(source.fileName))
            }
            if(Files.isDirectory(source))target.toString() else target.resolve(source.fileName).toString()
        }
        System.setProperty("capacity.childClasspath",entries.joinToString(java.io.File.pathSeparator))
        report["runtimeSnapshot"]="workspace build outputs copied before first role starts; dependency cache entries remain immutable"
    }

    private fun start(role:String,index:Int,url:String,pauseDelivery:Boolean=false):CapacityProcess =
        CapacityProcess(role,index,settings,url,directory,pauseDelivery).also { processes+=it;it.start();println("CAPACITY ready role=$role index=$index pid=${it.pid}") }

    private fun phaseDuration(name: String): Int =
        if (settings.scenario == "sustained" && name != "journal-plus-conversation") minOf(settings.durationSeconds, 1800)
        else settings.durationSeconds

    private fun phase(name:String,api:CapacityProcess,conversations:List<CapacityProcess>,journal:CapacityProcess?,
        jdbc:JdbcTemplate,load:CapacityLoad,burst:List<UUID>,clean:Boolean,postgres:PostgreSQLContainer,
        users:List<UserId>?):Map<String,Any?> {
        println("CAPACITY phase=$name")
        report["activePhase"] = name
        writeReport()
        val requests = ConcurrentLinkedQueue<CapacityRequest>()
        val participants = listOf(api)+conversations+listOfNotNull(journal)
        val samples = ConcurrentLinkedQueue<Map<String,Any?>>()
        val sampler = Executors.newSingleThreadScheduledExecutor()
        val cleaned = java.util.concurrent.atomic.AtomicLong()
        val journalPolicy=java.util.concurrent.atomic.AtomicReference<Map<String,Any>?>()
        val journalPolicyFailure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val statBefore = statementTotals(jdbc)
        val statementsBefore=statementDetails(jdbc).associateBy { it["queryid"] }
        val databaseBefore=databaseActivity(jdbc)
        val cpuBefore=postgres.execInContainer("cat","/sys/fs/cgroup/cpu.stat").stdout.trim()
        val start = Instant.now()
        val task = sampler.scheduleWithFixedDelay({
            runCatching {
                val rows = participants.map { mapper.convertValue(it.metrics(), Map::class.java) }
                val queues = jdbc.queryForMap("""
                    SELECT (SELECT count(*) FROM conversation.response_jobs WHERE status IN ('PENDING','PROCESSING')) response_backlog,
                        (SELECT count(*) FROM journal.generation_jobs WHERE status IN ('PENDING','PROCESSING')) journal_backlog,
                        (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock') lock_waiters,
                        (SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM conversation.response_jobs WHERE status IN ('PENDING','PROCESSING')) oldest_response_seconds,
                        (SELECT coalesce(extract(epoch FROM now()-min(created_at)),0) FROM journal.generation_jobs WHERE status IN ('PENDING','PROCESSING')) oldest_journal_seconds
                """.trimIndent())
                val waits=jdbc.queryForList("""
                    SELECT application_name,state,coalesce(wait_event_type,'CPU-or-runnable') wait_type,
                        coalesce(wait_event,'none') wait_event,count(*) connections
                    FROM pg_stat_activity WHERE datname=current_database() AND state<>'idle' AND pid<>pg_backend_pid()
                    GROUP BY application_name,state,wait_event_type,wait_event
                """.trimIndent())
                val writes = jdbc.queryForMap("""
                    SELECT c.num_timed,c.num_requested,c.write_time,c.sync_time,c.buffers_written,
                        w.wal_sync,w.wal_sync_time,w.wal_write_time
                    FROM pg_stat_checkpointer c CROSS JOIN pg_stat_wal w
                """.trimIndent())
                val sample = mapOf("at" to Instant.now().toString(),"roles" to rows,"queues" to queues,
                    "databaseWaits" to waits,"databaseWrites" to writes)
                samples.add(sample)
                Files.writeString(directory.resolve("$name-metrics.ndjson"),mapper.writeValueAsString(sample)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND)
                if(clean) participants.filter { it.role!="api" }.forEach { cleaned.addAndGet(it.clean().toLong()) }
                if (burst.size>=2 && journalPolicy.get()==null && journalPolicyFailure.get()==null) {
                    runCatching { load.verifyJournalPolicy(api.apiPort,burst[0],burst[1]) }
                        .onSuccess { if(it!=null)journalPolicy.set(it) }.onFailure { journalPolicyFailure.set(it) }
                }
            }.onFailure { println("CAPACITY metrics sample unavailable: ${it.javaClass.simpleName}") }
        },0,1,TimeUnit.SECONDS)
        val duration = phaseDuration(name)
        val injection = try { load.run(api.apiPort,settings.rps,duration,requests,users) }
            finally {
                task.cancel(false)
                sampler.shutdown()
                if(!sampler.awaitTermination(30,TimeUnit.SECONDS)) {
                    sampler.shutdownNow()
                    error("부하 관측·정책 검증 종료가 30초 안에 완료되지 않았습니다")
                }
            }
        val statAfter = statementTotals(jdbc)
        val databaseAfter=databaseActivity(jdbc)
        val topDelta=topStatements(jdbc,statementsBefore)
        val cpuAfter=postgres.execInContainer("cat","/sys/fs/cgroup/cpu.stat").stdout.trim()
        journalPolicyFailure.get()?.let { throw AssertionError("부하 중 구독 수정·지연 원본 검증 실패",it) }
        val responsesDrained = drain(load,requests,settings.drainSeconds)
        val waits = load.waitingTimes(requests,conversations)
        val integrity = load.verifyStored(requests)
        val errors = requests.count { it.status==0 || it.status>=500 }
        val latency = percentiles(requests.filter { it.status==200 }.map { it.latencyMs })
        val waiting = percentiles(waits)
        val waitSamplesDropped = load.waitSamplesDropped(conversations)
        val oldest = samples.mapNotNull { (it["queues"] as? Map<*,*>)?.get("response_backlog") as? Number }.maxOfOrNull { it.toLong() } ?: 0
        val journalDrainStarted=System.nanoTime()
        if (burst.isNotEmpty() && settings.journalDrainSeconds>0) {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.journalDrainSeconds.toLong())
            while(System.nanoTime()<end && journalOutstanding(jdbc,burst)>0)Thread.sleep(500)
        }
        val burstStored = if(burst.isEmpty())0 else jdbc.dataSource!!.connection.use { connection ->
            val array = connection.createArrayOf("uuid",burst.toTypedArray())
            try { connection.prepareStatement("SELECT count(*) FROM journal.journals WHERE record_day_id=ANY(?)").use {
                it.setArray(1,array);it.executeQuery().use { rows->rows.next();rows.getInt(1) }
            } } finally { array.free() }
        }
        val journalPolicyAfterDrain = if (burstStored >= 2 && journalPolicy.get() == null) {
            load.verifyJournalPolicy(api.apiPort, burst[0], burst[1])
        } else null
        val summary = linkedMapOf(
            "name" to name,"durationSeconds" to duration,"startedAt" to start.toString(),"injection" to injection,
            "preparedUsers" to (users?.size ?: 0),
            "apiLatency" to latency,"endToEndLatency" to percentiles(requests.map { it.endToEndMs }),
            "generatorLag" to percentiles(requests.map { it.generatorLagMs }),
            "httpStatuses" to requests.groupingBy { it.status.toString() }.eachCount(),
            "errorRate" to errors.toDouble()/requests.size.coerceAtLeast(1),
            "responseWaiting" to waiting,"waitSamplesDropped" to waitSamplesDropped,"responseStates" to load.responseStatus(requests),
            "responsesDrained" to responsesDrained,"integrity" to integrity,"maxResponseBacklog" to oldest,
            "journalBurst" to burst.size,"journalsCompletedAfterDrains" to burstStored,
            "journalAdditionalDrainSeconds" to (System.nanoTime()-journalDrainStarted)/1000000000.0,
            "journalCompletion" to journalCompletion(jdbc,burst),
            "journalPolicyDuringLoad" to journalPolicy.get(),
            "journalPolicyAfterDrain" to journalPolicyAfterDrain,
            "cleanupDeleted" to cleaned.get(),"metricSamples" to samples.size,
            "resourcePeaks" to resourcePeaks(samples),
            "maxLockWaiters" to samples.maxOfOrNull { ((it["queues"] as Map<*,*>)["lock_waiters"] as Number).toLong() },
            "databaseStatementsBefore" to statBefore,"databaseStatementsAfter" to statAfter,
            "databaseActivityBefore" to databaseBefore,"databaseActivityAfter" to databaseAfter,
            "topDatabaseStatements" to topDelta,
            "databaseCgroupCpuBefore" to cpuBefore,"databaseCgroupCpuAfter" to cpuAfter,
            "rolesAfter" to participants.map { mapper.convertValue(it.metrics(),Map::class.java) },
            "apiGoalPassed" to (((latency["p95Ms"] as? Double) ?: Double.MAX_VALUE)<=300 &&
                ((latency["p99Ms"] as? Double) ?: Double.MAX_VALUE)<=1000 && errors.toDouble()/requests.size.coerceAtLeast(1)<0.001 &&
                injection["generatorRejected"]==0L && requests.isNotEmpty() && requests.all { it.status==200 && it.messageId!=null && it.jobId!=null }),
            // 최초 진입 표본이 빠진 워커가 있으면 다른 워커의 나중 시각이 섞일 수 있어 대기 목표를 판정하지 않는다
            "responseWaitingGoalPassed" to (responsesDrained && waitSamplesDropped==0L && ((waiting["p95Ms"] as? Double) ?: Double.MAX_VALUE)<=2000),
        )
        Files.writeString(directory.resolve("$name.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary))
        println("CAPACITY result phase=$name api=$latency waiting=$waiting states=${summary["responseStates"]}")
        writeReport()
        // 보고서를 남긴 뒤 판정한다. 측정 목적의 시험에서도 의도한 부하를 만들지 못했다면 결과를 해석할 수 없다.
        check(injection["generatorRejected"]==0L) { "부하 주입기가 예정 요청을 보내지 못했습니다(도구 실패): phase=$name, injection=$injection" }
        return summary
    }

    private fun drain(load:CapacityLoad,requests:Collection<CapacityRequest>,seconds:Int):Boolean {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end) {
            val states=load.responseStatus(requests)
            if((states["PENDING"]?:0)==0L && (states["PROCESSING"]?:0)==0L)return true
            Thread.sleep(500)
        }
        return false
    }

    // 대화 실행 주기마다 첫 시도에 장애를 주입한다. 기록당 실행 주기는 여러 번일 수 있으므로 작업 수가 아니라
    // 주기마다 남는 ASSISTANT 수와 비교하고, 재시도 성공·누락·중복을 각각 확인한다.
    private fun faults(api:CapacityProcess,conversations:List<CapacityProcess>,jdbc:JdbcTemplate,load:CapacityLoad):List<Map<String,Any>> =
        listOf("429","timeout","5xx").map { fault ->
            check(awaitNoActiveResponses(jdbc,60)) { "장애 주입 전 진행 중인 대화 작업이 남았습니다: fault=$fault" }
            val failuresBefore=conversations.sumOf { it.metrics()["providerFailures"].asLong() }
            conversations.forEach { it.update(CapacityControl(settings.conversationDelayMs.toLong(),settings.journalDelayMs.toLong(),fault,1)) }
            // 매 주입은 새 사용자로 보내 이번 구간의 기록만 대상으로 한다
            val requests=ConcurrentLinkedQueue<CapacityRequest>()
            load.run(api.apiPort,5,5,requests)
            check(requests.size==25 && requests.all { it.status==200 && it.jobId!=null }) { "장애 주입 대상 대화 25건 접수 실패" }
            check(drain(load,requests,60)) { "장애 주입 대화가 시간 안에 완료되지 않았습니다: fault=$fault" }
            check(awaitNoActiveResponses(jdbc,30)) { "장애 주입 후 진행 중인 대화 작업이 남았습니다: fault=$fault" }
            val records=requests.mapNotNull { it.jobId }.distinct()
            val states=load.responseStatus(requests)
            check((states["COMPLETED"]?:0)==records.size.toLong()) { "장애 주입 대화의 응답 작업이 모두 완료되지 않았습니다: $states" }
            val integrity=load.verifyStored(requests)
            val responses=load.responseSummary(records)
            val injectedFailures=conversations.sumOf { it.metrics()["providerFailures"].asLong() }-failuresBefore
            conversations.forEach { it.update(CapacityControl(settings.conversationDelayMs.toLong(),settings.journalDelayMs.toLong())) }
            check(injectedFailures>0) { "장애가 실제로 주입되지 않았습니다: fault=$fault" }
            check(responses.unansweredRecords==0L) { "재시도 후에도 답하지 않은 입력이 남았습니다: fault=$fault" }
            check(responses.duplicateResponses==0L) { "같은 범위에 중복 응답이 저장됐습니다: fault=$fault" }
            // 각 실행 주기는 첫 시도 실패 → 재시도 성공으로 ASSISTANT 하나를 남긴다
            check(injectedFailures==responses.assistantResponses) {
                "주입 실패 수와 재시도 성공 응답 수가 다릅니다: fault=$fault, injected=$injectedFailures, responses=${responses.assistantResponses}"
            }
            check(responses.attemptCounts.keys==setOf(2)) { "마지막 실행 주기가 한 번의 재시도로 끝나지 않았습니다: ${responses.attemptCounts}" }
            mapOf("fault" to fault,"injectedFailures" to injectedFailures,"records" to records.size,
                "assistantResponses" to responses.assistantResponses,"finalAttemptCounts" to responses.attemptCounts,
                "responseStates" to states,"integrity" to integrity)
        }

    private fun awaitNoActiveResponses(jdbc:JdbcTemplate,seconds:Int):Boolean {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds.toLong())
        while(System.nanoTime()<end) {
            if(jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs WHERE status IN ('PENDING','PROCESSING')")==0L)return true
            Thread.sleep(500)
        }
        return false
    }

    private fun journalRecovery(journal:CapacityProcess,url:String,fixtures:CapacityFixtures,jdbc:JdbcTemplate,
        api:CapacityProcess,conversations:List<CapacityProcess>,load:CapacityLoad):Map<String,Any> {
        val beforeFaultDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
        while(jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE status IN ('PENDING','PROCESSING')")!!>0 && System.nanoTime()<beforeFaultDeadline)Thread.sleep(500)
        check(jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE status IN ('PENDING','PROCESSING')")==0)
        journal.update(CapacityControl(settings.conversationDelayMs.toLong(),30000))
        val ids=fixtures.journalBurst(3)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
        while(journal.metrics()["providerActive"].asInt()==0 && System.nanoTime()<deadline)Thread.sleep(100)
        check(journal.metrics()["providerActive"].asInt()>0)
        val killed=journal.pid
        journal.kill()
        val interrupted=jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE status='PROCESSING' AND record_day_id IN (?,?,?)",
            *ids.toTypedArray())!!
        check(interrupted>0) { "시험 대상 처리 중 작업 없이 종료됐습니다" }
        val offlineRequests=ConcurrentLinkedQueue<CapacityRequest>()
        load.run(api.apiPort,5,5,offlineRequests)
        check(offlineRequests.size==25 && offlineRequests.all { it.status==200 }) { "Journal 종료 중 API 접수 실패" }
        check(drain(load,offlineRequests,60))
        check(load.responseStatus(offlineRequests)["COMPLETED"]==offlineRequests.mapNotNull { it.jobId }.distinct().size.toLong()) { "Journal 종료 중 대화 처리 실패" }
        val offlineIntegrity=load.verifyStored(offlineRequests)
        val offlineWaiting=percentiles(load.waitingTimes(offlineRequests,conversations))
        val replacement=start("journal-worker",1,url)
        val recoveryStart=System.nanoTime()
        val end=System.nanoTime()+TimeUnit.MINUTES.toNanos(7)
        var nextProgress=recoveryStart
        var completed=0
        while(System.nanoTime()<end) {
            completed=jdbc.queryForObject<Int>("SELECT count(*) FROM journal.journals WHERE record_day_id IN (?,?,?)", *ids.toTypedArray())!!
            if(completed==ids.size)break
            if(System.nanoTime()>=nextProgress) {
                println("CAPACITY journal recovery completed=$completed/${ids.size} elapsedSeconds=${(System.nanoTime()-recoveryStart)/1000000000}; waiting for production lease")
                nextProgress=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            }
            Thread.sleep(1000)
        }
        check(completed==ids.size) { "강제 종료 후 일기 회수 실패" }
        return mapOf("killedPid" to killed,"replacementPid" to replacement.pid,"interruptedProcessing" to interrupted,
            "whileJournalOffline" to mapOf("requests" to 25,"apiLatency" to percentiles(offlineRequests.map { it.latencyMs }),
                "responseWaiting" to offlineWaiting,"waitSamplesDropped" to load.waitSamplesDropped(conversations),"integrity" to offlineIntegrity),
            "recoveredJournals" to completed,"recoverySeconds" to (System.nanoTime()-recoveryStart)/1000000000.0,
            "leaseNote" to "production five-minute generation lease, no database lease timestamp injection")
    }

    private fun deliveryRecovery(url:String,fixtures:CapacityFixtures,jdbc:JdbcTemplate):List<Map<String,Any>> {
        processes.last { it.role=="journal-worker" }.kill()
        return listOf(false,true).mapIndexed { index,after ->
            val interrupted=start("journal-worker",2+index*2,url,pauseDelivery=true)
            val id=fixtures.journalBurst(1).single()
            val crash=interrupted.crashDelivery(after)
            check(crash["recordDayId"].asString()==id.toString())
            val registeredAtCrash=jdbc.queryForObject<Int>("SELECT count(*) FROM journal.generation_jobs WHERE record_day_id=?",id)!!
            check(registeredAtCrash==if(after)1 else 0)
            val replacement=start("journal-worker",3+index*2,url)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
            while(System.nanoTime()<deadline) {
                val acknowledged=jdbc.queryForObject<Boolean>("SELECT delivery_acknowledged FROM conversation.record_days WHERE id=?",id)!!
                val completed=jdbc.queryForObject<Int>("SELECT count(*) FROM journal.journals WHERE record_day_id=?",id)!!
                if(acknowledged && completed==1)break
                Thread.sleep(500)
            }
            val state=jdbc.queryForMap("""
                SELECT delivery_acknowledged,delivery_version,
                    (SELECT count(*) FROM journal.generation_jobs WHERE record_day_id=?) jobs,
                    (SELECT count(*) FROM journal.journals WHERE record_day_id=?) journals
                FROM conversation.record_days WHERE id=?
            """.trimIndent(),id,id,id)
            check(state["delivery_acknowledged"]==true && (state["delivery_version"] as Number).toLong()>crash["version"].asLong())
            check((state["jobs"] as Number).toInt()==1 && (state["journals"] as Number).toInt()==1)
            replacement.kill()
            mapOf("afterRegistration" to after,"killedPid" to interrupted.pid,"replacementPid" to replacement.pid,
                "registeredAtCrash" to registeredAtCrash,"recoveredState" to state,
                "leaseNote" to "production one-minute delivery lease; actual Runtime.halt between independent transactions")
        }
    }

    private fun journalOutstanding(jdbc:JdbcTemplate,ids:List<UUID>):Int = jdbc.dataSource!!.connection.use { connection ->
        val array=connection.createArrayOf("uuid",ids.toTypedArray())
        try { connection.prepareStatement("""
            SELECT count(*) FROM conversation.record_days r WHERE r.id=ANY(?)
                AND (r.closed_at IS NULL OR NOT r.delivery_acknowledged OR EXISTS (
                    SELECT 1 FROM journal.generation_jobs j WHERE j.record_day_id=r.id AND j.status IN ('PENDING','PROCESSING')))
        """.trimIndent()).use {
            it.setArray(1,array);it.executeQuery().use { rows->rows.next();rows.getInt(1) }
        } } finally { array.free() }
    }

    private fun journalCompletion(jdbc:JdbcTemplate, ids:List<UUID>):Map<String,Any> {
        if(ids.isEmpty())return mapOf("requested" to 0)
        val durations=jdbc.dataSource!!.connection.use { connection ->
            val array=connection.createArrayOf("uuid",ids.toTypedArray())
            try { connection.prepareStatement("""
                SELECT g.updated_at,r.closed_at FROM journal.journals j
                JOIN conversation.record_days r ON r.id=j.record_day_id
                JOIN journal.generation_jobs g ON g.record_day_id=j.record_day_id AND g.status='COMPLETED'
                WHERE j.record_day_id=ANY(?)
            """.trimIndent()).use {
                it.setArray(1,array)
                it.executeQuery().use { rows -> buildList { while(rows.next())add(Duration.between(rows.getTimestamp(2).toInstant(),rows.getTimestamp(1).toInstant()).toNanos()/1000000.0) } }
            } } finally { array.free() }
        }
        return mapOf("requested" to ids.size,"completed" to durations.size,"deadlineSeconds" to 3600,
            "completedWithinDeadline" to durations.count { it<=3600000 },
            "latency" to percentiles(durations),
            "goalPassed" to (durations.count { it<=3600000 }>= kotlin.math.ceil(ids.size*0.99).toInt()))
    }

    private fun resourcePeaks(samples:Collection<Map<String,Any?>>):Map<String,Any> {
        val rows = samples.flatMap { (it["roles"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList() }
        return rows.groupBy { it["role"].toString()+"-"+it["pid"] }.mapValues { (_,series) ->
            val keys=listOf("heapUsedBytes","dbActive","dbTotal","dbPending","providerActive","telemetryDropped","consoleDropped")
            keys.associateWith { key -> series.maxOf { (it[key] as Number).toDouble() } }
        }
    }

    private fun databaseSize(jdbc:JdbcTemplate):Map<String,Any> = mapOf(
        "databaseBytes" to jdbc.queryForObject<Long>("SELECT pg_database_size(current_database())")!!,
        "tables" to jdbc.queryForList("""
            SELECT schemaname,relname,n_live_tup,pg_total_relation_size(relid) total_bytes
            FROM pg_stat_user_tables ORDER BY schemaname,relname
        """.trimIndent()),
        "sourceRows" to jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages WHERE role='USER'")!!,
        "assistantRows" to jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages WHERE role='ASSISTANT'")!!,
    )
    private fun statementTotals(jdbc:JdbcTemplate)=jdbc.queryForMap("""
        SELECT coalesce(sum(calls),0) calls,coalesce(sum(rows),0) rows,coalesce(sum(total_exec_time),0) total_exec_ms,
            coalesce(sum(shared_blks_hit),0) shared_hits,coalesce(sum(shared_blks_read),0) shared_reads,
            coalesce(sum(temp_blks_written),0) temp_writes
        FROM pg_stat_statements
    """.trimIndent())
    private fun databaseActivity(jdbc:JdbcTemplate)=jdbc.queryForMap("""
        SELECT d.xact_commit,d.xact_rollback,d.blks_read,d.blks_hit,d.blk_read_time,d.blk_write_time,
            d.temp_bytes,d.deadlocks,w.wal_bytes,w.wal_buffers_full,w.wal_write,w.wal_sync,w.wal_write_time,w.wal_sync_time
        FROM pg_stat_database d CROSS JOIN pg_stat_wal w WHERE d.datname=current_database()
    """.trimIndent())
    private fun statementDetails(jdbc:JdbcTemplate)=jdbc.queryForList("""
        SELECT queryid,query,calls,rows,total_exec_time,shared_blks_hit,shared_blks_read
        FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
    """.trimIndent())
    private fun topStatements(jdbc:JdbcTemplate,before:Map<Any?,Map<String,Any?>>)=
        statementDetails(jdbc).map { current ->
            val previous=before[current["queryid"]]
            fun delta(key:String)=(current[key] as Number).toDouble()-((previous?.get(key) as? Number)?.toDouble() ?: 0.0)
            val calls=delta("calls")
            mapOf("query" to current["query"],"calls" to calls,"rows" to delta("rows"),
                "total_exec_time" to delta("total_exec_time"),"mean_exec_time" to if(calls>0)delta("total_exec_time")/calls else 0.0,
                "shared_blks_hit" to delta("shared_blks_hit"),"shared_blks_read" to delta("shared_blks_read"))
        }.filter { (it["calls"] as Number).toDouble()>0 }
            .sortedByDescending { (it["total_exec_time"] as Number).toDouble() }.take(15)
    private fun writeReport() { Files.writeString(directory.resolve("report.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report)) }
}
