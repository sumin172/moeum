package com.moeum.conversation.infrastructure.scheduling

import com.moeum.kernel.TimeProvider
import com.moeum.platform.job.CompletedJobRetentionProperties
import com.moeum.platform.observability.SafeFailure
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp

/** 기록 잠금으로 재시도와 정리를 직렬화한다. 후속 완료를 보고 재시도를 막는 FAILED 작업이 있으면 정리하지 않는다. */
@Component
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
class CompletedResponseJobCleaner(
    private val jdbc: JdbcTemplate,
    private val time: TimeProvider,
    private val properties: CompletedJobRetentionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cleanBatch(): Int = jdbc.update("""
        WITH candidates AS (
            SELECT j.id FROM conversation.response_jobs j
            JOIN conversation.record_days r ON r.id=j.record_day_id
            WHERE j.status='COMPLETED' AND j.updated_at<?
                AND r.closed_at IS NOT NULL
                AND NOT EXISTS (
                    SELECT 1 FROM conversation.response_jobs other
                    WHERE other.record_day_id=j.record_day_id AND other.status<>'COMPLETED')
            ORDER BY j.updated_at,j.id LIMIT ?
            FOR UPDATE OF r,j SKIP LOCKED
        )
        DELETE FROM conversation.response_jobs WHERE id IN (SELECT id FROM candidates)
    """.trimIndent(), Timestamp.from(time.now().minus(properties.completedAge)), properties.batchSize)

    @Scheduled(scheduler = "conversationRetentionScheduler", fixedDelayString = $$"${moeum.jobs.retention.interval:PT1M}", initialDelayString = $$"${moeum.jobs.retention.initial-delay:PT1M}")
    fun scheduledCleanup() {
        runCatching { cleanBatch() }
            .onSuccess { if (it > 0) log.info("완료 응답 작업 정리: deleted={}", it) }
            .onFailure { log.warn("완료 응답 작업 정리 실패: code={}", SafeFailure.code(it)) }
    }
}
