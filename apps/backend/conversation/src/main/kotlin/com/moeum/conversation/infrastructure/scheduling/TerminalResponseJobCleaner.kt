package com.moeum.conversation.infrastructure.scheduling

import com.moeum.kernel.TimeProvider
import com.moeum.platform.job.JobRetentionProperties
import com.moeum.platform.observability.SafeFailure
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp

/** 닫힌 기록의 오래된 종료 작업을 작은 배치로 정리한다. 응답 본문은 메시지에 남는다. */
@Component
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
class TerminalResponseJobCleaner(
    private val jdbc: JdbcTemplate,
    private val time: TimeProvider,
    private val properties: JobRetentionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cleanBatch(): Int = jdbc.update("""
        WITH candidates AS (
            SELECT j.record_day_id FROM conversation.response_jobs j
            JOIN conversation.record_days r ON r.id=j.record_day_id
            WHERE j.status IN ('COMPLETED','FAILED') AND j.updated_at<=?
                AND r.closed_at IS NOT NULL
            ORDER BY j.updated_at,j.record_day_id LIMIT ?
            FOR UPDATE OF r,j SKIP LOCKED
        )
        DELETE FROM conversation.response_jobs WHERE record_day_id IN (SELECT record_day_id FROM candidates)
    """.trimIndent(), Timestamp.from(time.now().minus(properties.terminalAge)), properties.batchSize)

    @Scheduled(scheduler = "conversationRetentionScheduler", fixedDelayString = $$"${moeum.jobs.retention.interval:PT10S}", initialDelayString = $$"${moeum.jobs.retention.initial-delay:PT1M}")
    fun scheduledCleanup() {
        runCatching {
            val deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos()
            var deleted = 0
            repeat(10) {
                val count = cleanBatch()
                deleted += count
                if (count < properties.batchSize || System.nanoTime() >= deadline) return@runCatching deleted
            }
            deleted
        }
            .onSuccess { if (it > 0) log.info("종료 응답 작업 정리: deleted={}", it) }
            .onFailure { log.warn("종료 응답 작업 정리 실패: code={}", SafeFailure.code(it)) }
    }
}
