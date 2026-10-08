package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.ResponseFailureReason
import com.moeum.platform.job.JobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "response_jobs", schema = "conversation")
class ResponseJobJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "user_message_id", nullable = false)
    val userMessageId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "day_date", nullable = false)
    val dayDate: LocalDate,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val status: JobStatus,
    @Column(name = "failure_reason")
    @Enumerated(EnumType.STRING)
    val failureReason: ResponseFailureReason?,
    @Column(name = "attempt_count", nullable = false)
    val attemptCount: Int,
    @Column(name = "next_attempt_at", nullable = false)
    val nextAttemptAt: Instant,
    @Column(name = "lease_expires_at")
    val leaseExpiresAt: Instant?,
    @Column(name = "last_error_code")
    val lastErrorCode: String?,
    @Version
    @Column(nullable = false)
    val version: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
)
