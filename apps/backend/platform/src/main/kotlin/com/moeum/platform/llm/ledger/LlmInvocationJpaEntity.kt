package com.moeum.platform.llm.ledger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "llm_invocations", schema = "platform")
class LlmInvocationJpaEntity(
    @field:Id
    @field:Column(name = "id")
    private val entityId: UUID,
    @Column(nullable = false)
    val purpose: String,
    @Column(name = "user_id")
    val userId: UUID?,
    @Column(nullable = false)
    val provider: String,
    @Column(nullable = false)
    val model: String,
    @Column(name = "prompt_version", nullable = false)
    val promptVersion: String,
    @Column(name = "generation_id")
    val generationId: UUID?,
    @Column(name = "input_tokens", nullable = false)
    val inputTokens: Int,
    @Column(name = "output_tokens", nullable = false)
    val outputTokens: Int,
    @Column(name = "cached_input_tokens", nullable = false)
    val cachedInputTokens: Int,
    @Column(name = "latency_ms", nullable = false)
    val latencyMs: Long,
    @Column(nullable = false)
    val succeeded: Boolean,
    @Column(name = "finish_reason")
    val finishReason: String?,
    @Column(name = "error_code")
    val errorCode: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) : Persistable<UUID> {
    override fun getId(): UUID = entityId

    // append-only라 항상 새 row다 — merge(SELECT 후 INSERT) 대신 바로 INSERT하게 한다.
    override fun isNew(): Boolean = true
}
