package com.moeum.integration.support

import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** 테스트 권한 데이터만 삽입한다. 운영용 지급 서비스를 테스트 때문에 유지하지 않는다. */
fun insertPremiumSubscription(jdbc: JdbcTemplate, user: UserId, endsAt: Instant? = null): UUID {
    val id = UUID.randomUUID()
    val now = Timestamp.from(Instant.now())
    jdbc.update("""
        INSERT INTO identity.subscriptions(id,user_id,plan,starts_at,ends_at,created_at)
        VALUES (?,?,'PREMIUM',?,?,?)
    """.trimIndent(),id,user.value,now,endsAt?.let(Timestamp::from),now)
    return id
}
