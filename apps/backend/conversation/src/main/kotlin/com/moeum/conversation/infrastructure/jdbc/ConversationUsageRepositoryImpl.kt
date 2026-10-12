package com.moeum.conversation.infrastructure.jdbc

import com.moeum.conversation.domain.ConversationUsageRepository
import com.moeum.conversation.domain.ConversationUsage
import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.sql.Timestamp

@Repository
class ConversationUsageRepositoryImpl(private val jdbc: JdbcTemplate) : ConversationUsageRepository {
    override fun consume(userId: UserId, date: LocalDate, minute: Instant, limit: Int, requestsPerMinute: Int): Boolean =
        jdbc.query("""
            INSERT INTO conversation.conversation_usage(user_id,usage_date,message_count,request_window,request_count)
            VALUES (?, ?, 1, ?, 1)
            ON CONFLICT (user_id) DO UPDATE SET
                usage_date=EXCLUDED.usage_date,
                message_count=CASE WHEN conversation_usage.usage_date=EXCLUDED.usage_date THEN conversation_usage.message_count+1 ELSE 1 END,
                request_window=EXCLUDED.request_window,
                request_count=CASE WHEN conversation_usage.request_window=EXCLUDED.request_window THEN conversation_usage.request_count+1 ELSE 1 END
            WHERE (conversation_usage.usage_date<>EXCLUDED.usage_date OR conversation_usage.message_count<?)
                AND (conversation_usage.request_window<>EXCLUDED.request_window OR conversation_usage.request_count<?)
            RETURNING message_count
            """.trimIndent(), { rs, _ -> rs.getInt(1) }, userId.value, date, Timestamp.from(minute), limit, requestsPerMinute).isNotEmpty()

    override fun find(userId: UserId): ConversationUsage? =
        jdbc.query("SELECT * FROM conversation.conversation_usage WHERE user_id=?", { rs, _ ->
            ConversationUsage(rs.getObject("usage_date", LocalDate::class.java), rs.getInt("message_count"),
                rs.getTimestamp("request_window").toInstant(), rs.getInt("request_count"))
        }, userId.value).firstOrNull()
}
