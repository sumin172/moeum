package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.MomentExtractionJobStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "moment_extraction_jobs", schema = "conversation")
class MomentExtractionJobJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "conversation_day_id", nullable = false)
    val conversationDayId: UUID,
    @Column(name = "source_revision", nullable = false)
    val sourceRevision: Long,
    @Column(name = "request_key", nullable = false)
    val requestKey: String,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val status: MomentExtractionJobStatus,
    @Column(name = "attempt_count", nullable = false)
    val attemptCount: Int,
    @Column(name = "error_code")
    val errorCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
