package com.moeum.conversation.infrastructure.jdbc

import com.moeum.conversation.domain.RecordDayRepository
import com.moeum.conversation.domain.model.RecordDay
import com.moeum.conversation.domain.model.UserRecordState
import com.moeum.conversation.infrastructure.config.RecordDayProperties
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

@Repository
class RecordDayRepositoryImpl(private val jdbc: JdbcTemplate, private val recordDayProperties: RecordDayProperties) : RecordDayRepository {
    override fun lockState(userId: UserId): UserRecordState {
        val lockSql="SELECT * FROM conversation.user_record_states WHERE user_id = ? FOR UPDATE"
        jdbc.query(lockSql,stateMapper,userId.value).firstOrNull()?.let { return it }
        return jdbc.query("""
            INSERT INTO conversation.user_record_states(user_id,close_time) VALUES (?,?)
            ON CONFLICT (user_id) DO NOTHING RETURNING *
        """.trimIndent(),stateMapper,userId.value,recordDayProperties.defaultCloseTime).firstOrNull()
            ?: jdbc.query(lockSql,stateMapper,userId.value).single()
    }

    override fun findState(userId: UserId): UserRecordState? =
        jdbc.query("SELECT * FROM conversation.user_record_states WHERE user_id = ?", stateMapper, userId.value).firstOrNull()

    override fun saveState(state: UserRecordState) {
        jdbc.update("UPDATE conversation.user_record_states SET close_time = ? WHERE user_id = ?",
            state.closeTime, state.userId.value)
    }

    override fun find(userId: UserId, id: RecordDayId, lock: Boolean): RecordDay? =
        jdbc.query("SELECT * FROM conversation.record_days WHERE user_id = ? AND id = ?" + if (lock) " FOR UPDATE" else "",
            dayMapper, userId.value, id.value).firstOrNull()

    override fun findOpen(userId: UserId, lock: Boolean): RecordDay? =
        jdbc.query("SELECT * FROM conversation.record_days WHERE user_id = ? AND closed_at IS NULL" + if (lock) " FOR UPDATE" else "",
            dayMapper, userId.value).firstOrNull()

    override fun latestClosed(userId: UserId): RecordDay? =
        jdbc.query("SELECT * FROM conversation.record_days WHERE user_id = ? AND closed_at IS NOT NULL ORDER BY start_at DESC LIMIT 1",
            dayMapper, userId.value).firstOrNull()

    override fun list(userId: UserId, displayDate: LocalDate?, limit: Int): List<RecordDay> =
        if (displayDate == null) jdbc.query("SELECT * FROM conversation.record_days WHERE user_id = ? ORDER BY start_at DESC, id DESC LIMIT ?", dayMapper, userId.value, limit)
        else jdbc.query("SELECT * FROM conversation.record_days WHERE user_id = ? AND display_date = ? ORDER BY start_at DESC, id DESC LIMIT ?", dayMapper, userId.value, displayDate, limit)

    override fun insert(day: RecordDay) {
        jdbc.update(
            """INSERT INTO conversation.record_days
                (id,user_id,display_date,start_at,close_date,close_time,timezone,close_at,
                 source_revision,closed_at,delivery_version,delivery_lease_expires_at,delivery_acknowledged)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            day.id.value, day.userId.value, day.displayDate, ts(day.startAt), day.closeDate, day.closeTime,
            day.timezone, ts(day.closeAt), day.sourceRevision, day.closedAt?.let(::ts),
            day.deliveryVersion, day.deliveryLeaseExpiresAt?.let(::ts), day.deliveryAcknowledged,
        )
    }

    override fun update(day: RecordDay) {
        jdbc.update(
            """UPDATE conversation.record_days SET timezone=?,close_at=?,source_revision=?,
                closed_at=? WHERE user_id=? AND id=?""",
            day.timezone, ts(day.closeAt), day.sourceRevision, day.closedAt?.let(::ts),
            day.userId.value, day.id.value,
        )
    }

    // 잠금 순서: 사용자 상태를 SKIP LOCKED로 먼저 잡고 해당 열린 기록을 잠근다.
    // 원본·본문을 스캔하지 않으며 한 poll에서 작은 예약 후보만 조사한다.
    override fun claimDueClosure(now: Instant): RecordDay? {
        val candidates = jdbc.query(
            "SELECT user_id,id FROM conversation.record_days WHERE closed_at IS NULL AND close_at <= ? ORDER BY close_at LIMIT 32",
            { rs, _ -> UserId(rs.getObject("user_id", UUID::class.java)) to RecordDayId(rs.getObject("id", UUID::class.java)) },
            ts(now),
        )
        for ((userId, id) in candidates) {
            jdbc.query("SELECT user_id FROM conversation.user_record_states WHERE user_id=? FOR UPDATE SKIP LOCKED",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, userId.value).firstOrNull() ?: continue
            val day = jdbc.query("SELECT * FROM conversation.record_days WHERE user_id=? AND id=? AND closed_at IS NULL AND close_at<=? FOR UPDATE SKIP LOCKED",
                dayMapper, userId.value, id.value, ts(now)).firstOrNull() ?: continue
            return day
        }
        return null
    }

    override fun claimDelivery(now: Instant, leaseExpiresAt: Instant): RecordDay? =
        jdbc.query(
            """UPDATE conversation.record_days SET delivery_version=delivery_version+1, delivery_lease_expires_at=?
               WHERE id=(SELECT id FROM conversation.record_days
                 WHERE closed_at IS NOT NULL AND NOT delivery_acknowledged
                   AND (delivery_lease_expires_at IS NULL OR delivery_lease_expires_at<=?)
                 ORDER BY closed_at LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *""",
            dayMapper, ts(leaseExpiresAt), ts(now),
        ).firstOrNull()

    override fun acknowledgeDelivery(userId: UserId, id: RecordDayId, version: Long): Boolean =
        jdbc.update("""UPDATE conversation.record_days SET delivery_acknowledged=true, delivery_lease_expires_at=NULL
            WHERE user_id=? AND id=? AND delivery_version=? AND NOT delivery_acknowledged""", userId.value, id.value, version) == 1
}

private fun ts(value: Instant) = java.sql.Timestamp.from(value)
private fun ResultSet.instant(name: String): Instant? = getTimestamp(name)?.toInstant()
private val stateMapper = RowMapper { rs: ResultSet, _: Int ->
    UserRecordState(UserId(rs.getObject("user_id", UUID::class.java)), rs.getObject("close_time", LocalTime::class.java))
}
private val dayMapper = RowMapper { rs: ResultSet, _: Int ->
    RecordDay(
        id=RecordDayId(rs.getObject("id", UUID::class.java)), userId=UserId(rs.getObject("user_id", UUID::class.java)),
        displayDate=rs.getObject("display_date", LocalDate::class.java), startAt=rs.instant("start_at")!!,
        closeDate=rs.getObject("close_date", LocalDate::class.java), closeTime=rs.getObject("close_time", LocalTime::class.java),
        timezone=rs.getString("timezone"), closeAt=rs.instant("close_at")!!,
        sourceRevision=rs.getLong("source_revision"), closedAt=rs.instant("closed_at"),
        deliveryVersion=rs.getLong("delivery_version"), deliveryLeaseExpiresAt=rs.instant("delivery_lease_expires_at"),
        deliveryAcknowledged=rs.getBoolean("delivery_acknowledged"),
    )
}
