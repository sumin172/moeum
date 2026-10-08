package com.moeum.platform.llm.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface LlmInvocationJpaRepository : JpaRepository<LlmInvocationJpaEntity, UUID>

@Component
class JpaLlmInvocationLedger(
    private val jpaRepository: LlmInvocationJpaRepository,
) : LlmInvocationLedger {

    // 호출부 트랜잭션과 무관하게 남긴다(호출부가 롤백돼도 "호출했다"는 사실과 비용은 실제로 발생했다).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun record(invocation: LlmInvocation) {
        jpaRepository.save(
            LlmInvocationJpaEntity(
                entityId = invocation.id,
                purpose = invocation.purpose,
                userId = invocation.userId?.value,
                provider = invocation.provider,
                model = invocation.model,
                promptVersion = invocation.promptVersion,
                generationId = invocation.generationId,
                inputTokens = invocation.inputTokens,
                outputTokens = invocation.outputTokens,
                cachedInputTokens = invocation.cachedInputTokens,
                latencyMs = invocation.latencyMs,
                succeeded = invocation.succeeded,
                finishReason = invocation.finishReason,
                errorCode = invocation.errorCode,
                createdAt = invocation.createdAt,
            ),
        )
    }
}
