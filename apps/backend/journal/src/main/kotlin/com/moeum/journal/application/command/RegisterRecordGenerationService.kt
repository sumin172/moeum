package com.moeum.journal.application.command
import com.moeum.conversation.application.publicapi.RecordDelivery
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UuidV7
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
@Service
class RegisterRecordGenerationService(private val jdbc: JdbcTemplate, private val time: TimeProvider) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun register(delivery: RecordDelivery): Boolean {
        val now = Timestamp.from(time.now())
        return jdbc.update("""
            INSERT INTO journal.generation_jobs
            (id,user_id,record_day_id,display_date,status,attempt_count,next_attempt_at,version,created_at,updated_at)
            SELECT ?,?,?,?,'PENDING',0,?,0,?,?
            WHERE NOT EXISTS (SELECT 1 FROM journal.journals WHERE user_id=? AND record_day_id=?)
            ON CONFLICT (user_id,record_day_id) DO NOTHING
        """.trimIndent(), UuidV7.generate(), delivery.userId.value, delivery.recordDayId.value, delivery.displayDate,
            Timestamp.from(delivery.closedAt), now, now, delivery.userId.value, delivery.recordDayId.value) == 1
    }
}
