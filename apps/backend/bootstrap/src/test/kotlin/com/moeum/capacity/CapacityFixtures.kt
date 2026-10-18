package com.moeum.capacity

import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 모든 fixture는 이 시험의 disposable DB에만 들어간다. 오래된 업무 데이터 이전 코드가 아니다. */
class CapacityFixtures(private val jdbc: JdbcTemplate) {
    fun users(count: Int): List<UserId> {
        val users = List(count) { UserId.generate() }
        jdbc.batchUpdate("INSERT INTO identity.users(id,google_id,email,created_at) VALUES (?,?,?,?)",
            users.map { arrayOf<Any>(it.value, "capacity-" + it.value, "synthetic@example.invalid", Timestamp.from(Instant.now())) })
        jdbc.batchUpdate("INSERT INTO identity.subscriptions(id,user_id,plan,starts_at,created_at) VALUES (?,?,'PREMIUM',?,?)",
            users.filterIndexed { i, _ -> i % 10 == 0 }.map {
                arrayOf<Any>(UUID.randomUUID(), it.value, Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now()))
            })
        return users
    }

    fun history(records: Int) {
        if (records == 0) return
        val users = (records + 179) / 180
        jdbc.execute("""
            INSERT INTO identity.users(id,google_id,email,created_at)
            SELECT md5('hu'||i)::uuid,'history-'||i,'history@example.invalid',now()-interval '180 days'
            FROM generate_series(0,$users-1) i;
            INSERT INTO conversation.user_record_states(user_id,close_time)
            SELECT md5('hu'||i)::uuid,'02:00'::time FROM generate_series(0,$users-1) i;
            INSERT INTO conversation.record_days
            (id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,last_user_occurred_at,
                stream_sequence,source_revision,closed_at,close_reason,frozen_source_revision,delivery_acknowledged)
            SELECT md5('hr'||i)::uuid,md5('hu'||(i%$users))::uuid,
                (current_date-(i/$users+1))::date, (current_date-(i/$users+1))::timestamptz,
                (current_date-(i/$users))::date,'00:00'::time,'UTC',
                (current_date-(i/$users))::timestamptz,
                (current_date-(i/$users+1))::timestamptz+interval '20 minutes',
                CASE WHEN i%100=0 THEN 39 ELSE 40 END,20,(current_date-(i/$users))::timestamptz,'SCHEDULED',20,true
            FROM generate_series(0,$records-1) i;
        """.trimIndent())
        var offset = 0
        while (offset < records) {
            val end = minOf(offset + 2000, records) - 1
            jdbc.execute(
                """
                INSERT INTO conversation.messages
                (id,user_id,role,content,occurred_at,timezone,record_day_id,source_sequence,stream_sequence,
                    client_message_id,generation_id,model,prompt_version,created_at)
                SELECT md5('hm'||i||':'||s||':'||r)::uuid,md5('hu'||(i%$users))::uuid,
                    CASE WHEN r=0 THEN 'USER' ELSE 'ASSISTANT' END,
                    substr(repeat(md5(i||':'||s||':'||r),32),1,1000),
                    (current_date-(i/$users+1))::timestamptz+s*interval '1 minute'+r*interval '1 second','UTC',
                    md5('hr'||i)::uuid,s,2*s-1+r-CASE WHEN i%100=0 AND s>1 THEN 1 ELSE 0 END,
                    CASE WHEN r=0 THEN md5('hc'||i||':'||s)::uuid  END,
                    CASE WHEN r=1 THEN md5('hg'||i||':'||s)::uuid  END,
                    CASE WHEN r=1 THEN 'synthetic-v1'  END,CASE WHEN r=1 THEN 'v1'  END,
                    (current_date-(i/$users+1))::timestamptz+s*interval '1 minute'+r*interval '1 second'
                FROM generate_series($offset,$end) i CROSS JOIN generate_series(1,20) s CROSS JOIN generate_series(0,1) r
                WHERE NOT (i%100=0 AND s=1 AND r=1);
                INSERT INTO conversation.response_jobs
                (id,user_message_id,user_id,record_day_id,input_source_revision,status,failure_reason,attempt_count,
                    next_attempt_at,version,created_at,updated_at)
                SELECT md5('hj'||i||':'||s)::uuid,md5('hm'||i||':'||s||':0')::uuid,md5('hu'||(i%$users))::uuid,md5('hr'||i)::uuid,s,
                    CASE WHEN i%100=0 AND s=1 THEN 'FAILED' ELSE 'COMPLETED' END,
                    CASE WHEN i%100=0 AND s=1 THEN 'GENERATION_FAILED'  END,1,
                    (current_date-(i/$users))::timestamptz,1,(current_date-(i/$users+1))::timestamptz,
                    (current_date-(i/$users))::timestamptz
                FROM generate_series($offset,$end) i CROSS JOIN generate_series(1,20) s
                WHERE i/$users<=35 OR i%100=0;
                INSERT INTO journal.journals
                (id,user_id,record_day_id,display_date,title,body,version,source_revision,generation_id,model,prompt_version,generated_at)
                SELECT md5('hjournal'||i)::uuid,md5('hu'||(i%$users))::uuid,md5('hr'||i)::uuid,
                    (current_date-(i/$users+1))::date,'synthetic',substr(repeat(md5(i::text),32),1,1000),
                    0,20,md5('hjgen'||i)::uuid,'synthetic-v1','v1',(current_date-(i/$users))::timestamptz
                FROM generate_series($offset,$end) i;
                INSERT INTO journal.generation_jobs
                (id,journal_id,user_id,record_day_id,display_date,status,attempt_count,next_attempt_at,version,created_at,updated_at)
                SELECT md5('hgenjob'||i)::uuid,md5('hjournal'||i)::uuid,md5('hu'||(i%$users))::uuid,md5('hr'||i)::uuid,
                    (current_date-(i/$users+1))::date,'COMPLETED',1,(current_date-(i/$users))::timestamptz,1,
                    (current_date-(i/$users+1))::timestamptz,(current_date-(i/$users))::timestamptz
                FROM generate_series($offset,$end) i WHERE i/$users<=35;
            """.trimIndent())
            offset = end + 1
            if (offset % 10000 == 0) println("CAPACITY history records=$offset/$records")
        }
        jdbc.execute("ANALYZE")
    }

    /** 마감 원본을 직접 넣는다. API 접수·quota 검증과 별개인 배치 부하 fixture다. */
    fun journalBurst(count: Int): List<UUID> {
        if (count == 0) return emptyList()
        return TransactionTemplate(DataSourceTransactionManager(jdbc.dataSource!!)).execute {
        val users = users(count)
        val records = List(count) { UUID.randomUUID() }
        val start = Instant.now().minusSeconds(86400)
        val end = Instant.now().minusSeconds(1)
        jdbc.batchUpdate("""
            INSERT INTO conversation.user_record_states(user_id,close_time,active_record_day_id) VALUES (?,'02:00',?)
        """.trimIndent(), users.indices.map { arrayOf<Any>(users[it].value, records[it]) })
        jdbc.batchUpdate("""
            INSERT INTO conversation.record_days(id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,
                last_user_occurred_at,stream_sequence,source_revision,close_reason)
            VALUES (?,?,current_date-1,?,current_date,'02:00','UTC',?,?,20,20,'SCHEDULED')
        """.trimIndent(), users.indices.map { arrayOf<Any>(records[it], users[it].value, Timestamp.from(start), Timestamp.from(end), Timestamp.from(start.plusSeconds(1200))) })
        jdbc.batchUpdate("""
            INSERT INTO conversation.messages(id,user_id,role,content,occurred_at,timezone,record_day_id,source_sequence,
                stream_sequence,client_message_id,created_at)
            VALUES (?,?,'USER',?,?, 'UTC',?,?,?, ?,?)
        """.trimIndent(), users.indices.flatMap { i -> (1..20).map { seq ->
            arrayOf<Any>(UUID.randomUUID(), users[i].value, "synthetic journal source ".repeat(40),
                Timestamp.from(start.plusSeconds(seq * 60L)), records[i], seq, seq, UUID.randomUUID(), Timestamp.from(start.plusSeconds(seq * 60L)))
        } })
        records
        }
    }
}
