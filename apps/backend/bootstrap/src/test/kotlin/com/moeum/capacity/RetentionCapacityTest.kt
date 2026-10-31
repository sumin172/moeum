package com.moeum.capacity

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** 실제 30일 대기 대신 만료 작업을 지속 주입한다. 운영 정리 주기·쿼리·보존 조건은 그대로다. */
@Tag("capacity")
@EnabledIfSystemProperty(named = "capacity.retention", matches = "true")
class RetentionCapacityTest {
    @Test fun `scheduled retention balances arrivals and recovers after worker interruption`() {
        val settings = CapacitySettings()
        require(settings.durationSeconds >= 120)
        require(settings.conversationReplicas == 1) { "정리 시험은 대화 워커 한 개를 사용합니다" }
        val directory = Path.of("build", "capacity", "retention-" + Instant.now().toString().replace(':', '-')).toAbsolutePath()
        Files.createDirectories(directory)
        System.setProperty("capacity.childClasspath", Files.readString(Path.of(System.getProperty("capacity.classpathFile"))))
        val mapper = JsonMapper.builder().build()
        val report = linkedMapOf<String, Any?>("settings" to settings, "startedAt" to Instant.now().toString(),
            "scope" to "fake AI, 30-day expiry compressed by old timestamps; actual 10-second retention scheduler",
            "state" to "running", "databaseCpu" to 4, "databaseMemoryBytes" to 4L*1024*1024*1024,
            "roleHeapBytes" to 512L*1024*1024, "terminalRecordsPer10Seconds" to 13)
        fun save() = Files.writeString(directory.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        val processes = CopyOnWriteArrayList<CapacityProcess>()
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withUsername("test").withPassword("test")
            .withCreateContainerCmdModifier { it.hostConfig!!.withMemory(4L*1024*1024*1024).withNanoCPUs(4_000_000_000L) }
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            save()
            postgres.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl=postgres.jdbcUrl; username="test"; password="test"; maximumPoolSize=2; minimumIdle=1
            }).use { pool ->
                val jdbc = JdbcTemplate(pool)
                fun start(role: String, index: Int = 0) = CapacityProcess(role,index,settings,postgres.jdbcUrl,directory,
                    retentionEnabled=true).also { processes += it; it.start() }
                val api = start("api")
                val fixture = CapacityFixtures(jdbc)
                fixture.history(settings.historyRecords)
                val originalSources = jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages")!!
                val conversation = start("conversation-worker")
                var journal = start("journal-worker")
                val initialJournalJobs=jdbc.queryForObject<Long>("SELECT count(*) FROM journal.generation_jobs")!!
                val burst = fixture.journalBurst(settings.journalBurst)
                val requests = ConcurrentLinkedQueue<CapacityRequest>()
                CapacityLoad(fixture,jdbc).use { load ->
                    val warm = ConcurrentLinkedQueue<CapacityRequest>()
                    if(settings.warmupSeconds>0) {
                        val warming=executor.submit<Map<String,Any>> { load.run(api.apiPort,settings.rps,settings.warmupSeconds,warm) }
                        try { while(!warming.isDone) {
                            val observation=mapOf("at" to Instant.now().toString(),"waits" to databaseWaits(jdbc))
                            Files.writeString(directory.resolve("warmup-waits.ndjson"),mapper.writeValueAsString(observation)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND)
                            Thread.sleep(1000)
                        } } catch(error:Throwable) { warming.cancel(true); throw error }
                        report["warmupInjection"]=warming.get()
                        report["warmupHttpStatuses"]=warm.groupingBy { it.status }.eachCount()
                        report["warmupResponseStates"]=load.responseStatus(warm)
                        save()
                        check(warm.all { it.status==200 }) { "워밍업 접수 실패" }
                        val warmEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.drainSeconds.toLong())
                        while(System.nanoTime()<warmEnd && load.responseStatus(warm)["COMPLETED"]!=jobCount(warm)) Thread.sleep(100)
                        val warmStates=load.responseStatus(warm)
                        report["warmupResponseStates"]=warmStates
                        save()
                        check(warmStates["COMPLETED"]==jobCount(warm)) { "워밍업 실패" }
                    }
                    val initialResponseJobs=jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs")!!
                    val measurementStartedAt=Instant.now()
                    report["measurementStartedAt"]=measurementStartedAt.toString()
                    val begun = System.nanoTime()
                    val injection = executor.submit<Map<String,Any>> { load.run(api.apiPort,settings.rps,settings.durationSeconds,requests) }
                    var batches = 0
                    var interrupted = false
                    var restarted = false
                    var restart: Future<CapacityProcess>? = null
                    val outageAt = settings.durationSeconds / 2
                    val outageSeconds = minOf(60, settings.durationSeconds / 6)
                    try { while (!injection.isDone) {
                        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-begun)
                        val dueBatches=minOf((seconds/10+1).toInt(),(settings.durationSeconds+9)/10)
                        while(batches<dueBatches) { fixture.terminalArrivals(13); batches++ }
                        if (!interrupted && seconds >= outageAt) {
                            journal.kill()
                            interrupted=true
                            report["workerStoppedAtSeconds"]=seconds
                        }
                        if (interrupted && restart==null && seconds >= outageAt+outageSeconds) {
                            report["journalEligibleBeforeRestart"]=eligible(jdbc,"journal")
                            report["workerRestartRequestedAtSeconds"]=seconds
                            restart=executor.submit<CapacityProcess> { start("journal-worker",1) }
                        }
                        if (!restarted && restart?.isDone==true) {
                            journal=restart.get()
                            restarted=true
                            report["workerRestartedAtSeconds"]=TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-begun)
                        }
                        val sample = mapOf("at" to Instant.now().toString(), "elapsedSeconds" to seconds,
                            "responseEligible" to eligible(jdbc,"conversation"), "journalEligible" to eligible(jdbc,"journal"),
                            "sources" to jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages"),
                            "responseJobs" to jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs"),
                            "journalJobs" to jdbc.queryForObject<Long>("SELECT count(*) FROM journal.generation_jobs"),
                            "activeResponseJobs" to jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs WHERE status IN ('PENDING','PROCESSING')"),
                            "databaseWaits" to databaseWaits(jdbc),
                            "databaseBytes" to jdbc.queryForObject<Long>("SELECT pg_database_size(current_database())"),
                            "roles" to (listOf(api,conversation)+if(!interrupted || restarted) listOf(journal) else emptyList()).map { mapper.convertValue(it.metrics(),Map::class.java) })
                        if(seconds>=180) report["steadyResponseEligiblePeak"]=maxOf((report["steadyResponseEligiblePeak"] as? Long) ?: 0L,sample["responseEligible"] as Long)
                        Files.writeString(directory.resolve("samples.ndjson"),mapper.writeValueAsString(sample)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND)
                        report["elapsedSeconds"]=seconds; report["terminalBatches"]=batches
                        save()
                        println("RETENTION elapsed=$seconds responseEligible=${sample["responseEligible"]} journalEligible=${sample["journalEligible"]}")
                        Thread.sleep(10_000)
                    } } catch(error:Throwable) { injection.cancel(true); throw error }
                    report["injection"]=injection.get()
                    while(batches<(settings.durationSeconds+9)/10) { fixture.terminalArrivals(13); batches++ }
                    report["terminalBatches"]=batches
                    val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.drainSeconds.toLong())
                    while (System.nanoTime()<end && (eligible(jdbc,"conversation")>0 || eligible(jdbc,"journal")>0 ||
                            load.responseStatus(requests).any { it.key != "COMPLETED" && it.value>0 })) Thread.sleep(1000)
                    val states = load.responseStatus(requests)
                    report["responses"]=states
                    report["integrity"]=load.verifyStored(requests)
                    val latency = percentiles(requests.filter { it.status==200 }.map { it.latencyMs })
                    report["apiLatency"]=latency
                    val waitingWindows=sortedMapOf<Long,MutableList<Double>>()
                    report["responseWaiting"]=percentiles(load.waitingTimes(requests,listOf(conversation)) { created,wait ->
                        val window=Duration.between(measurementStartedAt,created).seconds/30*30
                        waitingWindows.getOrPut(window) { mutableListOf() }.add(wait)
                    })
                    report["responseWaitingBy30Seconds"]=waitingWindows.map { (seconds,values) -> mapOf("fromSeconds" to seconds,"waiting" to percentiles(values)) }
                    report["waitSamplesDropped"]=load.waitSamplesDropped(listOf(conversation))
                    report["responseEligibleRemaining"]=eligible(jdbc,"conversation")
                    report["journalEligibleRemaining"]=eligible(jdbc,"journal")
                    report["responseJobsDeleted"]=initialResponseJobs+batches*13L+jobCount(requests)-jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs")!!
                    val recentJournalJobs=jdbc.queryForObject<Long>("SELECT count(*) FROM journal.generation_jobs WHERE created_at>now()-interval '1 day'")!!
                    report["recentJournalJobs"]=recentJournalJobs
                    report["journalJobsDeleted"]=initialJournalJobs+recentJournalJobs+batches*13L-jdbc.queryForObject<Long>("SELECT count(*) FROM journal.generation_jobs")!!
                    report["journalsCompleted"]=jdbc.queryForObject<Long>("SELECT count(*) FROM journal.journals WHERE title='synthetic journal'")
                    report["originalSources"]=originalSources
                    report["sourceRowsFinal"]=jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages")
                    report["tableSizes"]=jdbc.queryForList("SELECT schemaname,relname,pg_total_relation_size(relid) bytes FROM pg_stat_user_tables ORDER BY schemaname,relname")
                    val injectionResult=report["injection"] as Map<*,*>
                    check(injectionResult["generatorRejected"]==0L && requests.size==settings.rps*settings.durationSeconds && requests.all { it.status==200 }) { "API 접수 실패" }
                    check((latency["p95Ms"] as Double)<=300 && (latency["p99Ms"] as Double)<=1000) { "API 지연 목표 미달" }
                    check(states["COMPLETED"]==jobCount(requests)) { "응답 미완료" }
                    check(jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.response_jobs WHERE created_at>now()-interval '1 day'")==jobCount(requests+warm)) { "보존 기간 전 작업 삭제" }
                    // 최초 진입 표본이 빠지면 나중 호출 시각이 섞일 수 있어 대기 목표를 판정하지 않는다
                    check(report["waitSamplesDropped"]==0L) { "대기 표본 누락으로 응답 대기 판정 보류" }
                    check((report["responseWaiting"] as Map<*,*>)["p95Ms"].let { it as Double }<=2000) { "응답 대기 목표 미달" }
                    check(eligible(jdbc,"conversation")==0L && eligible(jdbc,"journal")==0L) { "종료 작업 적체 미회복" }
                    (report["steadyResponseEligiblePeak"] as? Long)?.let {
                        check(it<=52L) { "정리 적체가 40초 유입량 초과" }
                    }
                    check(restarted) { "정리 중단·재시작 미검증" }
                    check((report["journalsCompleted"] as Long)>=burst.size) { "일기 생성 미완료" }
                    check(report["sourceRowsFinal"]==originalSources+batches*507L+burst.size*20L+2L*(requests.size+warm.size)) { "보관 원본 수 불일치" }
                    report["state"]="passed"
                    report["completedAt"]=Instant.now().toString()
                }
            }
        } catch (error: Throwable) {
            report["state"]="failed"; report["failureType"]=error.javaClass.simpleName
            throw error
        } finally {
            save()
            executor.shutdownNow()
            processes.asReversed().forEach { runCatching { it.close() } }
            postgres.close()
            println("RETENTION report=$directory")
        }
    }

    private fun databaseWaits(jdbc:JdbcTemplate) = jdbc.queryForList("""
        SELECT application_name,state,coalesce(wait_event_type,'CPU-or-runnable') wait_type,
            coalesce(wait_event,'none') wait_event,count(*) connections,
            max(extract(epoch FROM now()-query_start)) max_query_seconds
        FROM pg_stat_activity WHERE datname=current_database() AND state<>'idle' AND pid<>pg_backend_pid()
        GROUP BY application_name,state,wait_event_type,wait_event
    """.trimIndent())

    // 응답 작업은 기록당 하나이므로 요청 수가 아니라 서로 다른 기록 수와 비교한다.
    private fun jobCount(requests: Collection<CapacityRequest>) = requests.mapNotNull { it.jobId }.distinct().size.toLong()

    private fun eligible(jdbc:JdbcTemplate, schema:String):Long = if(schema=="conversation") jdbc.queryForObject<Long>("""
        SELECT count(*) FROM conversation.response_jobs j JOIN conversation.record_days r ON r.id=j.record_day_id
        WHERE j.status IN ('COMPLETED','FAILED') AND j.updated_at<=now()-interval '30 days' AND r.closed_at IS NOT NULL
    """.trimIndent())!! else jdbc.queryForObject<Long>("""
        SELECT count(*) FROM journal.generation_jobs WHERE (status='COMPLETED' AND updated_at<=now()-interval '30 days')
            OR (status='FAILED' AND created_at<=now()-interval '30 days')
    """.trimIndent())!!
}
