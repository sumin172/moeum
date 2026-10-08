package com.moeum.platform.llm.ledger

import com.moeum.kernel.UserId
import java.time.Instant
import java.util.UUID

// LLM 호출 한 번의 기록(append-only). 비용·사용량 집계, 토큰 기준 quota, 모델 비교의 원천 데이터.
data class LlmInvocation(
    val id: UUID,
    val purpose: String,
    val userId: UserId?,
    val provider: String,
    val model: String,
    val promptVersion: String,
    // 성공한 호출만 값이 있다(생성 결과의 generationId와 같은 값)
    val generationId: UUID?,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val latencyMs: Long,
    val succeeded: Boolean,
    val finishReason: String?,
    val errorCode: String?,
    val createdAt: Instant,
)

interface LlmInvocationLedger {
    fun record(invocation: LlmInvocation)
}
