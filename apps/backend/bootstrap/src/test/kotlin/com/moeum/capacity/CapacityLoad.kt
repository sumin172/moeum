package com.moeum.capacity

import com.moeum.kernel.*
import com.moeum.platform.security.config.JwtProperties
import com.moeum.platform.security.jwt.JwtProviderImpl
import com.moeum.platform.security.jwt.IssuedAccessToken
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil

data class CapacityRequest(
    val clientId: UUID, val userId: UUID, val messageId: UUID?, val jobId: UUID?,
    val recordId: UUID?, val status: Int, val errorCode: String?,
    val latencyMs: Double, val endToEndMs: Double, val generatorLagMs: Double,
)

/** open-loop 주입: 느린 응답 때문에 예정 요청 수를 줄이지 않고, 주입기 포화도 명시적으로 실패로 기록한다. */
class CapacityLoad(private val fixtures: CapacityFixtures, private val jdbc: JdbcTemplate) : AutoCloseable {
    private val mapper = JsonMapper.builder().build()
    private val io = Executors.newVirtualThreadPerTaskExecutor()
    private val http = HttpClient.newBuilder().executor(io).connectTimeout(Duration.ofSeconds(3)).build()
    private val signer = JwtProviderImpl(JwtProperties(CapacitySettings.JWT_SECRET), object : TimeProvider { override fun now() = Instant.now() })
    private val tokens = ConcurrentHashMap<UserId, IssuedAccessToken>()

    fun run(port: Int, rps: Int, seconds: Int, requests: ConcurrentLinkedQueue<CapacityRequest>, users: List<UserId>? = null): Map<String, Any> {
        val offered = rps.toLong() * seconds
        return send(port, rps, offered, requests, users ?: fixtures.users(ceil(offered / 18.0).toInt().coerceAtLeast(10)))
    }

    fun prepareOpenRecords(port: Int, rps: Int, users: List<UserId>, requests: ConcurrentLinkedQueue<CapacityRequest>): Map<String, Any> =
        send(port, rps, users.size.toLong(), requests, users)

    private fun send(port: Int, rps: Int, offered: Long, requests: ConcurrentLinkedQueue<CapacityRequest>, users: List<UserId>): Map<String, Any> {
        require(offered <= 2000000) { "로컬 주입기 1회 요청 상한은 200만입니다" }
        require(users.isNotEmpty())
        val permits = Semaphore(512)
        val rejected = AtomicLong()
        val futures = mutableListOf<CompletableFuture<*>>()
        val started = System.nanoTime()
        for (i in 0 until offered) {
            check(!Thread.currentThread().isInterrupted) { "부하 주입이 중단됐습니다" }
            val planned = started + i * 1000000000L / rps
            while (System.nanoTime() < planned) LockSupport.parkNanos(minOf(planned - System.nanoTime(), 10000000))
            if (!permits.tryAcquire()) { rejected.incrementAndGet(); continue }
            val user = users[(i % users.size).toInt()]
            val token = tokens.compute(user) { _, existing ->
                if (existing == null || existing.expiresAt <= Instant.now().plusSeconds(30)) signer.issue(user) else existing
            }!!
            val clientId = UUID.randomUUID()
            val length = when (i % 10) { 0L -> 4000; 1L, 2L -> 200; else -> 1000 }
            val content = buildString {
                append("synthetic diary source ")
                while (this.length < length) append(UUID.randomUUID().toString().replace("-", ""))
            }.take(length)
            val zone = listOf("Asia/Seoul", "America/New_York", "Europe/Berlin", "Pacific/Auckland", "UTC")[(i % users.size % 5).toInt()]
            val payload = mapper.writeValueAsBytes(mapOf("clientMessageId" to clientId.toString(), "content" to content,
                "occurredAt" to Instant.now().toString(), "timezone" to zone))
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/conversations/messages"))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token.token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build()
            val dispatched = System.nanoTime()
            futures += http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
                val completed = System.nanoTime()
                val node = response?.let { runCatching { mapper.readTree(it.body()) }.getOrNull() }
                val message = node?.get("message")
                requests.add(CapacityRequest(clientId,user.value,uuid(message,"id"),uuid(message,"responseJobId"),uuid(node,"recordDayId"),
                    response?.statusCode() ?: 0, if (error != null) error.javaClass.simpleName else node?.get("code")?.asString(),
                    (completed-dispatched)/1000000.0,(completed-planned)/1000000.0,(dispatched-planned)/1000000.0))
                permits.release()
            }
            if (i > 0 && i % (rps * 30L) == 0L) println("CAPACITY offered=$i/$offered inflight=${512-permits.availablePermits()} generatorRejected=${rejected.get()}")
        }
        CompletableFuture.allOf(*futures.toTypedArray()).get(45, TimeUnit.SECONDS)
        val elapsed = (System.nanoTime() - started) / 1000000000.0
        return mapOf("offered" to offered, "dispatched" to requests.size, "generatorRejected" to rejected.get(),
            "elapsedSeconds" to elapsed, "dispatchedRps" to requests.size/elapsed)
    }

    fun verifyStored(requests: Collection<CapacityRequest>): Map<String, Any> {
        val accepted = requests.filter { it.status == 200 && it.messageId != null }
        val ids = accepted.mapNotNull { it.messageId }.distinct()
        val expected=accepted.associateBy { it.messageId }
        val count = if (ids.isEmpty()) 0 else withIds(ids) { connection, array ->
            connection.prepareStatement("SELECT id,user_id,client_message_id,record_day_id FROM conversation.messages WHERE role='USER' AND id=ANY(?)").use {
                it.setArray(1,array);it.executeQuery().use { rows ->
                    var stored=0
                    while(rows.next()) {
                        val request=expected.getValue(rows.getObject(1,UUID::class.java))
                        check(rows.getObject(2,UUID::class.java)==request.userId && rows.getObject(3,UUID::class.java)==request.clientId &&
                            rows.getObject(4,UUID::class.java)==request.recordId) { "접수 원본의 소유자·클라이언트 ID·기록 귀속 불일치" }
                        stored++
                    }
                    stored
                }
            }
        }
        check(count == ids.size) { "접수한 원본 유실: accepted=${ids.size}, stored=$count" }
        return mapOf("acceptedRequests" to accepted.size, "acceptedUniqueMessages" to ids.size, "storedAcceptedMessages" to count,
            "ownerClientAndRecordVerified" to true,
            "acceptedWithoutResponseJob" to accepted.count { it.jobId == null })
    }

    fun verifyJournalPolicy(port:Int,premiumId:UUID,freeId:UUID):Map<String,Any>? {
        fun journal(id:UUID)=jdbc.queryForList("""
            SELECT j.user_id,j.version,r.start_at
            FROM journal.journals j JOIN conversation.record_days r ON r.id=j.record_day_id
            WHERE j.record_day_id=?
        """.trimIndent(),id).singleOrNull()
        val original=journal(premiumId) ?: return null
        val free=journal(freeId) ?: return null
        val owner=original["user_id"] as UUID
        val beforeQuota=exchange(port,owner,"GET","/api/conversations/quota",null).second["used"].asInt()
        val sourceCount=jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages WHERE record_day_id=?",
            premiumId)!!
        val jobCount=jdbc.queryForObject<Long>("SELECT count(*) FROM journal.generation_jobs WHERE record_day_id=?",
            premiumId)!!
        val edit=mapOf("title" to "capacity edit","body" to "capacity user edit","version" to original["version"])
        check(exchange(port,free["user_id"] as UUID,"PUT","/api/journals/$freeId",edit).first==403)
        check(exchange(port,owner,"PUT","/api/journals/$premiumId",edit).first==200)
        check(exchange(port,owner,"PUT","/api/journals/$premiumId",edit).first==409)
        val late=exchange(port,owner,"POST","/api/conversations/messages",mapOf(
            "clientMessageId" to UUID.randomUUID().toString(),"content" to "synthetic late source",
            "occurredAt" to ((original["start_at"] as java.sql.Timestamp).toInstant().plusSeconds(300)).toString(),
            "timezone" to "UTC"))
        check(late.first==200 && late.second["message"]["content"].asString()=="synthetic late source")
        check(UUID.fromString(late.second["recordDayId"].asString())!=premiumId)
        check(exchange(port,owner,"GET","/api/conversations/quota",null).second["used"].asInt()==beforeQuota+1)
        val after=journal(premiumId)!!
        check((after["version"] as Number).toLong()==(original["version"] as Number).toLong()+1)
        check(jdbc.queryForObject<String>("SELECT body FROM journal.journals WHERE record_day_id=?", premiumId)=="capacity user edit")
        check(jdbc.queryForObject<Long>("SELECT count(*) FROM conversation.messages WHERE record_day_id=?", premiumId)==sourceCount)
        check(jdbc.queryForObject<Long>("SELECT count(*) FROM journal.generation_jobs WHERE record_day_id=?", premiumId)==jobCount)
        return mapOf("freeEditHttp" to 403,"premiumEditHttp" to 200,"staleEditHttp" to 409,
            "lateAccepted" to true,"nextRecordCreated" to true)
    }

    private fun exchange(port:Int,user:UUID,method:String,path:String,body:Any?):Pair<Int,JsonNode> {
        val request=HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
            .header("Authorization","Bearer "+signer.issue(UserId(user)).token).header("Content-Type","application/json")
            .method(method,if(body==null)HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build()
        val response=http.send(request,HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to mapper.readTree(response.body())
    }

    fun waitingTimes(requests: Collection<CapacityRequest>, processes: List<CapacityProcess>,
        observe: (Instant, Double) -> Unit = { _, _ -> }): List<Double> {
        val ids = requests.mapNotNull { it.jobId }.distinct()
        if (ids.isEmpty()) return emptyList()
        val created = withIds(ids) { connection, array ->
            connection.prepareStatement("SELECT id,created_at FROM conversation.response_jobs WHERE id=ANY(?)").use {
                it.setArray(1,array)
                it.executeQuery().use { rows -> buildMap { while(rows.next())put(rows.getObject(1,UUID::class.java).toString(),rows.getTimestamp(2).toInstant()) } }
            }
        }
        return processes.flatMap { process -> process.samples().mapNotNull { sample ->
                created[sample["jobId"].asString()]?.let {
                    val wait=Duration.between(it,Instant.parse(sample["startedAt"].asString())).toNanos()/1000000.0
                    observe(it,wait)
                    wait
                }
        } }
    }

    fun responseStatus(requests: Collection<CapacityRequest>): Map<String, Long> {
        val ids = requests.mapNotNull { it.jobId }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return withIds(ids) { connection, array ->
            connection.prepareStatement("SELECT status,count(*) FROM conversation.response_jobs WHERE id=ANY(?) GROUP BY status").use {
                it.setArray(1,array)
                it.executeQuery().use { rows -> buildMap { while(rows.next())put(rows.getString(1),rows.getLong(2)) } }
            }
        }
    }

    private fun <T> withIds(ids: List<UUID>, action: (java.sql.Connection, java.sql.Array)->T): T =
        jdbc.dataSource!!.connection.use { connection ->
            val array = connection.createArrayOf("uuid",ids.toTypedArray())
            try { action(connection,array) } finally { array.free() }
        }

    private fun uuid(node: JsonNode?, key: String): UUID? = node?.get(key)?.takeUnless { it.isNull }?.asString()?.let(UUID::fromString)

    override fun close() { http.close();io.close() }
}

internal fun percentiles(values: Collection<Double>): Map<String, Any> {
    if (values.isEmpty()) return mapOf("samples" to 0)
    val sorted = values.sorted()
    fun at(p: Double) = sorted[(ceil(sorted.size * p).toInt()-1).coerceIn(0,sorted.lastIndex)]
    return mapOf("samples" to sorted.size,"p50Ms" to at(0.50),"p95Ms" to at(0.95),"p99Ms" to at(0.99),"maxMs" to sorted.last())
}
