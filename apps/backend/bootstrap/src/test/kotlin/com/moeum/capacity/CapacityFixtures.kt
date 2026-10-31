package com.moeum.capacity

import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 모든 fixture는 이 시험의 disposable DB에만 들어간다. 오래된 업무 데이터 이전 코드가 아니다. */
class CapacityFixtures(private val jdbc: JdbcTemplate) {
    private val named = NamedParameterJdbcTemplate(jdbc)
    private fun transaction() = TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!))

    /** 주입한 만료 작업. 정리 결과를 운영 정리 조건의 복제가 아니라 ID로 직접 확인한다. */
    data class TerminalArrivals(val responseRecordIds: List<UUID>, val generationJobIds: List<UUID>, val messages: Long)

    /**
     * 30일 경과 작업의 지속 유입을 압축 재현한다. 원본과 기록은 정리 후에도 남는다.
     * 짝수 기록은 마지막 USER에 답하지 못한 채 최종 실패한 기록이다(답변 경계 19 < 접수 20). 나머지는 모두 답한 완료 기록이다.
     */
    fun terminalArrivals(records: Int): TerminalArrivals {
        val params = mapOf("prefix" to UUID.randomUUID().toString(), "records" to records)
        transaction().executeWithoutResult {
            named.update(ARRIVAL_USERS_SQL, params)
            named.update(ARRIVAL_RECORDS_SQL, params)
            named.update(ARRIVAL_MESSAGES_SQL, params)
            named.update(ARRIVAL_RESPONSE_JOBS_SQL, params)
            named.update(ARRIVAL_GENERATION_JOBS_SQL, params)
        }
        // 위에서 만든 ID와 같은 규칙으로 다시 계산한다
        fun ids(kind: String) = named.queryForList(ARRIVAL_IDS_SQL, params + ("kind" to kind), UUID::class.java).filterNotNull()
        return TerminalArrivals(ids("-r"), ids("-g"), records * 40L - records / 2)
    }

    /** 오래됐지만 정리 조건에 해당하지 않아 남아야 하는 작업. 워커가 시험 중 상태를 바꾸지 않도록 예약·리스를 먼 미래로 둔다. */
    data class ProtectedJobs(val responseStatuses: Map<UUID, String>, val generationStatuses: Map<UUID, String>)

    fun protectedJobs(): ProtectedJobs = transaction().execute {
        val now = Instant.now()
        val far = Timestamp.from(now.plus(Duration.ofDays(30)))
        val old = Timestamp.from(now.minus(Duration.ofDays(40)))
        val started = Timestamp.from(now.minus(Duration.ofDays(41)))
        val openRecord = UUID.randomUUID(); val pendingRecord = UUID.randomUUID(); val processingRecord = UUID.randomUUID()
        // 열린 기록은 마감 예정도 먼 미래라 시험 중 마감·전달되지 않는다
        listOf(openRecord to true, pendingRecord to false, processingRecord to false).forEach { (id, open) ->
            named.update(PROTECTED_RECORD_SQL, MapSqlParameterSource()
                .addValue("id", id).addValue("userId", UUID.randomUUID()).addValue("startAt", started)
                .addValue("closeAt", if (open) far else old)
                .addValue("closedAt", if (open) null else old, Types.TIMESTAMP))
        }
        val responses = mapOf(openRecord to "COMPLETED", pendingRecord to "PENDING", processingRecord to "PROCESSING")
        responses.forEach { (id, status) ->
            named.update(PROTECTED_RESPONSE_JOB_SQL, MapSqlParameterSource()
                .addValue("id", id).addValue("status", status).addValue("nextAttemptAt", far)
                .addValue("leaseExpiresAt", if (status == "PROCESSING") far else null, Types.TIMESTAMP).addValue("old", old))
        }
        // 일기 실패 작업은 생성 시각 기준이라 최근 생성이면 오래된 갱신 시각이어도 남는다
        val generations = mapOf(UUID.randomUUID() to "PENDING", UUID.randomUUID() to "PROCESSING", UUID.randomUUID() to "FAILED")
        generations.forEach { (id, status) ->
            named.update(PROTECTED_GENERATION_JOB_SQL, MapSqlParameterSource()
                .addValue("id", id).addValue("userId", UUID.randomUUID()).addValue("recordId", UUID.randomUUID())
                .addValue("status", status).addValue("nextAttemptAt", far)
                .addValue("leaseExpiresAt", if (status == "PROCESSING") far else null, Types.TIMESTAMP)
                .addValue("createdAt", if (status == "FAILED") Timestamp.from(now) else old).addValue("updatedAt", old))
        }
        ProtectedJobs(responses, generations)
    }

    fun users(count: Int): List<UserId> {
        val users = List(count) { UserId.generate() }
        jdbc.batchUpdate(USERS_SQL,
            users.map { arrayOf<Any>(it.value, "capacity-" + it.value, "synthetic@example.invalid", Timestamp.from(Instant.now())) })
        jdbc.batchUpdate(PREMIUM_SUBSCRIPTIONS_SQL,
            users.filterIndexed { i, _ -> i % 10 == 0 }.map {
                arrayOf<Any>(UUID.randomUUID(), it.value, Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now()))
            })
        return users
    }

    fun history(records: Int) {
        if (records == 0) return
        val users = (records + 179) / 180
        val params = mapOf("users" to users, "records" to records)
        named.update(HISTORY_USERS_SQL, params)
        named.update(HISTORY_STATES_SQL, params)
        named.update(HISTORY_RECORDS_SQL, params)
        var offset = 0
        while (offset < records) {
            val end = minOf(offset + 2000, records) - 1
            val chunk = params + mapOf("offset" to offset, "end" to end)
            named.update(HISTORY_MESSAGES_SQL, chunk)
            named.update(HISTORY_RESPONSE_JOBS_SQL, chunk)
            named.update(HISTORY_JOURNALS_SQL, chunk)
            named.update(HISTORY_GENERATION_JOBS_SQL, chunk)
            offset = end + 1
            if (offset % 10000 == 0) println("CAPACITY history records=$offset/$records")
        }
        jdbc.execute("ANALYZE")
    }

    /** 마감 원본을 직접 넣는다. API 접수·quota 검증과 별개인 배치 부하 fixture다. */
    fun journalBurst(count: Int): List<UUID> {
        if (count == 0) return emptyList()
        return transaction().execute {
            val users = users(count)
            val records = List(count) { UUID.randomUUID() }
            val start = Instant.now().minusSeconds(86400)
            val end = Instant.now().minusSeconds(1)
            jdbc.batchUpdate(BURST_STATES_SQL, users.indices.map { arrayOf<Any>(users[it].value) })
            jdbc.batchUpdate(BURST_RECORDS_SQL,
                users.indices.map { arrayOf<Any>(records[it], users[it].value, Timestamp.from(start), Timestamp.from(end)) })
            jdbc.batchUpdate(BURST_MESSAGES_SQL, users.indices.flatMap { i -> (1..20).map { seq ->
                arrayOf<Any>(UUID.randomUUID(), users[i].value, "synthetic journal source ".repeat(40),
                    Timestamp.from(start.plusSeconds(seq * 60L)), records[i], seq, seq, UUID.randomUUID(), Timestamp.from(start.plusSeconds(seq * 60L)))
            } })
            records
        }
    }
}

private const val ARRIVAL_USERS_SQL = """
    INSERT INTO identity.users(id,google_id,email,created_at)
    SELECT md5(:prefix||'-user'||i)::uuid,:prefix||'-'||i,'retention@example.invalid',now()-interval '31 days'
    FROM generate_series(1,:records) i"""
private const val ARRIVAL_RECORDS_SQL = """
    INSERT INTO conversation.record_days
        (id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,
         stream_sequence,source_revision,closed_at,delivery_acknowledged)
    SELECT md5(:prefix||'-r'||i)::uuid,md5(:prefix||'-user'||i)::uuid,current_date-32,
        now()-interval '32 days',current_date-31,'02:00','UTC',now()-interval '31 days',
        CASE WHEN i%2=0 THEN 39 ELSE 40 END,20,now()-interval '31 days',true FROM generate_series(1,:records) i"""
private const val ARRIVAL_MESSAGES_SQL = """
    INSERT INTO conversation.messages
        (id,user_id,role,content,occurred_at,record_day_id,source_sequence,stream_sequence,client_message_id,created_at)
    SELECT md5(:prefix||'-m'||i||':'||s||':'||r)::uuid,md5(:prefix||'-user'||i)::uuid,
        CASE WHEN r=0 THEN 'USER' ELSE 'ASSISTANT' END,repeat('synthetic ',100),
        now()-interval '32 days',md5(:prefix||'-r'||i)::uuid,s,2*s-1+r,
        CASE WHEN r=0 THEN md5(:prefix||'-c'||i||':'||s)::uuid END,now()-interval '32 days'
    FROM generate_series(1,:records) i CROSS JOIN generate_series(1,20) s CROSS JOIN generate_series(0,1) r
    WHERE NOT (i%2=0 AND s=20 AND r=1)"""
private const val ARRIVAL_RESPONSE_JOBS_SQL = """
    INSERT INTO conversation.response_jobs
        (record_day_id,user_id,observed_source_revision,status,attempt_count,next_attempt_at,version,created_at,updated_at)
    SELECT md5(:prefix||'-r'||i)::uuid,md5(:prefix||'-user'||i)::uuid,20,
        CASE WHEN i%2=0 THEN 'FAILED' ELSE 'COMPLETED' END,1,now()-interval '31 days',1,
        now()-interval '32 days',now()-interval '31 days'
    FROM generate_series(1,:records) i"""
private const val ARRIVAL_GENERATION_JOBS_SQL = """
    INSERT INTO journal.generation_jobs
        (id,user_id,record_day_id,status,attempt_count,next_attempt_at,version,created_at,updated_at)
    SELECT md5(:prefix||'-g'||i)::uuid,md5(:prefix||'-user'||i)::uuid,md5(:prefix||'-r'||i)::uuid,
        CASE WHEN i%2=0 THEN 'FAILED' ELSE 'COMPLETED' END,1,now()-interval '31 days',1,
        now()-interval '32 days',CASE WHEN i%2=0 THEN now() ELSE now()-interval '31 days' END
    FROM generate_series(1,:records) i"""
private const val ARRIVAL_IDS_SQL = "SELECT md5(:prefix||:kind||i)::uuid FROM generate_series(1,:records) i"

private const val PROTECTED_RECORD_SQL = """
    INSERT INTO conversation.record_days(id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,
        stream_sequence,source_revision,closed_at,delivery_acknowledged)
    VALUES (:id,:userId,current_date-41,:startAt,current_date+30,'02:00','UTC',:closeAt,0,0,:closedAt,true)"""
private const val PROTECTED_RESPONSE_JOB_SQL = """
    INSERT INTO conversation.response_jobs(record_day_id,user_id,observed_source_revision,status,attempt_count,
        next_attempt_at,lease_expires_at,version,created_at,updated_at)
    SELECT :id,user_id,0,:status,1,:nextAttemptAt,:leaseExpiresAt,1,:old,:old FROM conversation.record_days WHERE id=:id"""
private const val PROTECTED_GENERATION_JOB_SQL = """
    INSERT INTO journal.generation_jobs(id,user_id,record_day_id,status,attempt_count,next_attempt_at,lease_expires_at,
        version,created_at,updated_at)
    VALUES (:id,:userId,:recordId,:status,1,:nextAttemptAt,:leaseExpiresAt,1,:createdAt,:updatedAt)"""

private const val USERS_SQL = "INSERT INTO identity.users(id,google_id,email,created_at) VALUES (?,?,?,?)"
private const val PREMIUM_SUBSCRIPTIONS_SQL =
    "INSERT INTO identity.subscriptions(id,user_id,plan,starts_at,created_at) VALUES (?,?,'PREMIUM',?,?)"

private const val HISTORY_USERS_SQL = """
    INSERT INTO identity.users(id,google_id,email,created_at)
    SELECT md5('hu'||i)::uuid,'history-'||i,'history@example.invalid',now()-interval '180 days'
    FROM generate_series(0,:users-1) i"""
private const val HISTORY_STATES_SQL = """
    INSERT INTO conversation.user_record_states(user_id,close_time)
    SELECT md5('hu'||i)::uuid,'02:00'::time FROM generate_series(0,:users-1) i"""
private const val HISTORY_RECORDS_SQL = """
    INSERT INTO conversation.record_days
    (id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,
        stream_sequence,source_revision,closed_at,delivery_acknowledged)
    SELECT md5('hr'||i)::uuid,md5('hu'||(i%:users))::uuid,
        (current_date-(i/:users+1))::date, (current_date-(i/:users+1))::timestamptz,
        (current_date-(i/:users))::date,'00:00'::time,'UTC',
        (current_date-(i/:users))::timestamptz,
        CASE WHEN i%100=0 THEN 39 ELSE 40 END,20,(current_date-(i/:users))::timestamptz,true
    FROM generate_series(0,:records-1) i"""
// 실패 기록(i%100=0)은 마지막 USER에 답하지 못하고 최종 실패했다(답변 경계 19 < 접수 20)
private const val HISTORY_MESSAGES_SQL = """
    INSERT INTO conversation.messages
    (id,user_id,role,content,occurred_at,record_day_id,source_sequence,stream_sequence,
        client_message_id,created_at)
    SELECT md5('hm'||i||':'||s||':'||r)::uuid,md5('hu'||(i%:users))::uuid,
        CASE WHEN r=0 THEN 'USER' ELSE 'ASSISTANT' END,
        substr(repeat(md5(i||':'||s||':'||r),32),1,1000),
        (current_date-(i/:users+1))::timestamptz+s*interval '1 minute'+r*interval '1 second',
        md5('hr'||i)::uuid,s,2*s-1+r,
        CASE WHEN r=0 THEN md5('hc'||i||':'||s)::uuid END,
        (current_date-(i/:users+1))::timestamptz+s*interval '1 minute'+r*interval '1 second'
    FROM generate_series(:offset,:end) i CROSS JOIN generate_series(1,20) s CROSS JOIN generate_series(0,1) r
    WHERE NOT (i%100=0 AND s=20 AND r=1)"""
private const val HISTORY_RESPONSE_JOBS_SQL = """
    INSERT INTO conversation.response_jobs
    (record_day_id,user_id,observed_source_revision,status,attempt_count,
        next_attempt_at,version,created_at,updated_at)
    SELECT md5('hr'||i)::uuid,md5('hu'||(i%:users))::uuid,20,
        CASE WHEN i%100=0 THEN 'FAILED' ELSE 'COMPLETED' END,
        1,(current_date-(i/:users))::timestamptz,1,(current_date-(i/:users+1))::timestamptz,
        (current_date-(i/:users))::timestamptz
    FROM generate_series(:offset,:end) i
    WHERE i/:users<=35 OR i%100=0"""
private const val HISTORY_JOURNALS_SQL = """
    INSERT INTO journal.journals
    (id,user_id,record_day_id,display_date,title,body,version)
    SELECT md5('hjournal'||i)::uuid,md5('hu'||(i%:users))::uuid,md5('hr'||i)::uuid,
        (current_date-(i/:users+1))::date,'synthetic',substr(repeat(md5(i::text),32),1,1000),0
    FROM generate_series(:offset,:end) i"""
private const val HISTORY_GENERATION_JOBS_SQL = """
    INSERT INTO journal.generation_jobs
    (id,user_id,record_day_id,status,attempt_count,next_attempt_at,version,created_at,updated_at)
    SELECT md5('hgenjob'||i)::uuid,md5('hu'||(i%:users))::uuid,md5('hr'||i)::uuid,
        'COMPLETED',1,(current_date-(i/:users))::timestamptz,1,
        (current_date-(i/:users+1))::timestamptz,(current_date-(i/:users))::timestamptz
    FROM generate_series(:offset,:end) i WHERE i/:users<=35"""

private const val BURST_STATES_SQL = "INSERT INTO conversation.user_record_states(user_id,close_time) VALUES (?,'02:00')"
private const val BURST_RECORDS_SQL = """
    INSERT INTO conversation.record_days(id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,
        stream_sequence,source_revision)
    VALUES (?,?,current_date-1,?,current_date,'02:00','UTC',?,20,20)"""
private const val BURST_MESSAGES_SQL = """
    INSERT INTO conversation.messages(id,user_id,role,content,occurred_at,record_day_id,source_sequence,
        stream_sequence,client_message_id,created_at)
    VALUES (?,?,'USER',?,?,?,?,?,?,?)"""
