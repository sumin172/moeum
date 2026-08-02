package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.MessageResponseStatus
import com.moeum.conversation.domain.model.MessageRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "messages", schema = "conversation")
class MessageJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "conversation_day_id", nullable = false)
    val conversationDayId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val role: MessageRole,
    @Column(nullable = false)
    val content: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(nullable = false)
    val timezone: String,
    @Column(name = "local_date", nullable = false)
    val localDate: LocalDate,
    @Column(name = "client_message_id")
    val clientMessageId: UUID?,
    @Column(name = "response_status")
    @Enumerated(EnumType.STRING)
    val responseStatus: MessageResponseStatus?,
    @Column(name = "generation_id")
    val generationId: UUID? = null,
    @Column
    val model: String? = null,
    @Column(name = "prompt_version")
    val promptVersion: String? = null,
    @Column(name = "input_tokens")
    val inputTokens: Int? = null,
    @Column(name = "output_tokens")
    val outputTokens: Int? = null,
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
)
