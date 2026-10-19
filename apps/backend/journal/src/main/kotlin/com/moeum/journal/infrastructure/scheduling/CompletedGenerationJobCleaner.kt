package com.moeum.journal.infrastructure.scheduling

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

@Component
@ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
class CompletedGenerationJobCleaner(
    private val jdbc: JdbcTemplate,
    private val time: TimeProvider,
    private val properties: CompletedJobRetentionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun cleanBatch(): Int = jdbc.update("""
        WITH candidates AS (
            SELECT id FROM journal.generation_jobs
            WHERE status='COMPLETED' AND updated_at<?
            ORDER BY updated_at,id LIMIT ? FOR UPDATE SKIP LOCKED
        )
        DELETE FROM journal.generation_jobs WHERE id IN (SELECT id FROM candidates)
    """.trimIndent(), Timestamp.from(time.now().minus(properties.completedAge)), properties.batchSize)

    @Scheduled(scheduler = "journalRetentionScheduler", fixedDelayString = $$"${moeum.jobs.retention.interval:PT1M}", initialDelayString = $$"${moeum.jobs.retention.initial-delay:PT1M}")
    fun scheduledCleanup() {
        runCatching { cleanBatch() }
            .onSuccess { if (it > 0) log.info("완료 일기 작업 정리: deleted={}", it) }
            .onFailure { log.warn("완료 일기 작업 정리 실패: code={}", SafeFailure.code(it)) }
    }
}
