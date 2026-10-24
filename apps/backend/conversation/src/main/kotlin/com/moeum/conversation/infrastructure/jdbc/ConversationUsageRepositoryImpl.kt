package com.moeum.conversation.infrastructure.jdbc

import com.moeum.conversation.domain.ConversationUsageRepository
import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.sql.Timestamp

@Repository
class ConversationUsageRepositoryImpl(private val jdbc: JdbcTemplate) : ConversationUsageRepository {
    override fun consume(userId: UserId, minute: Instant, requestsPerMinute: Int): Boolean =
        jdbc.query("""
            INSERT INTO conversation.conversation_usage(user_id,request_window,request_count)
            VALUES (?, ?, 1)
            ON CONFLICT (user_id) DO UPDATE SET
                request_window=EXCLUDED.request_window,
                request_count=CASE WHEN conversation_usage.request_window=EXCLUDED.request_window THEN conversation_usage.request_count+1 ELSE 1 END
            WHERE conversation_usage.request_window<>EXCLUDED.request_window OR conversation_usage.request_count<?
            RETURNING request_count
            """.trimIndent(), { rs, _ -> rs.getInt(1) }, userId.value, Timestamp.from(minute), requestsPerMinute).isNotEmpty()
}
