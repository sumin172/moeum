package com.moeum.journal.infrastructure.scheduling

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

@Component
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class TerminalGenerationJobCleaner(
    private val jdbc: JdbcTemplate,
    private val time: TimeProvider,
    private val properties: JobRetentionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cleanBatch(): Int {
        val cutoff = Timestamp.from(time.now().minus(properties.terminalAge))
        return jdbc.update("""
        WITH candidates AS (
            SELECT id FROM journal.generation_jobs
            WHERE (status='COMPLETED' AND updated_at<=?) OR (status='FAILED' AND created_at<=?)
            ORDER BY updated_at,id LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM journal.generation_jobs WHERE id IN (SELECT id FROM candidates)
        """.trimIndent(), cutoff, cutoff, properties.batchSize)
    }

    @Scheduled(scheduler = "journalRetentionScheduler", fixedDelayString = $$"${moeum.jobs.retention.interval:PT10S}", initialDelayString = $$"${moeum.jobs.retention.initial-delay:PT1M}")
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
            .onSuccess { if (it > 0) log.info("종료 일기 작업 정리: deleted={}", it) }
            .onFailure { log.warn("종료 일기 작업 정리 실패: code={}", SafeFailure.code(it)) }
    }
}
