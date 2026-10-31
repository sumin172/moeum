package com.moeum.conversation.infrastructure.jpa

import com.moeum.platform.job.JobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "response_jobs", schema = "conversation")
class ResponseJobJpaEntity(
    @Id
    @Column(name = "record_day_id")
    val recordDayId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "observed_source_revision", nullable = false)
    val observedSourceRevision: Long,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val status: JobStatus,
    @Column(name = "attempt_count", nullable = false)
    val attemptCount: Int,
    @Column(name = "next_attempt_at", nullable = false)
    val nextAttemptAt: Instant,
    @Column(name = "lease_expires_at")
    val leaseExpiresAt: Instant?,
    @Version
    @Column(nullable = false)
    val version: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
