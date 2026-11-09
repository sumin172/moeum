package com.moeum.platform.llm

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import org.springframework.boot.context.properties.ConfigurationProperties

// 프로세스의 동시 호출 수는 워커 실행 슬롯(moeum.runtime.*-concurrency)이 정한다.
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@ConfigurationProperties(prefix = "moeum.llm")
data class LlmProperties(
    // 용도별 provider·모델 선택. 모델 교체나 provider 전환은 코드가 아니라 이 설정으로 한다.
    val routes: Map<String, LlmRoute> = emptyMap(),
)

data class LlmRoute(
    // provider 이름(gemini | claude | cloudflare)
    val provider: String,
    val model: String,
    val maxOutputTokens: Int,
    // 모델 내부 추론(thinking)에 쓸 토큰 예산. Cloudflare는 0일 때 /no_think 소프트 제어만 지원한다.
    // Gemini는 0이면 끄고, Claude는 이 값을 사용하지 않는다. null이면 provider 기본값.
    // Gemini 2.5+는 thinking 토큰이 maxOutputTokens에 포함되므로, 상한이 작은 용도에서는 0으로 꺼야 응답이 잘리지 않는다.
    val thinkingBudget: Int? = null,
)
