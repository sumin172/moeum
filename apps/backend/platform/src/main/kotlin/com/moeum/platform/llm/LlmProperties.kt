package com.moeum.platform.llm

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "moeum.llm")
data class LlmProperties(
    // 용도별 provider·모델 선택. 모델 교체나 provider 전환은 코드가 아니라 이 설정으로 한다.
    val routes: Map<String, LlmRoute> = emptyMap(),
    // provider별 동시 호출 상한(bulkhead). 트래픽이 몰려도 LLM 호출이 무제한으로 늘어나 rate limit과
    // DB 커넥션 경쟁을 일으키지 않게 한다.
    val maxConcurrentCalls: Map<String, Int> = emptyMap(),
    // 동시 호출 상한에 걸렸을 때 기다리는 최대 시간. 넘기면 LlmException — 비동기 작업이면 재시도로 넘어간다.
    val acquireTimeout: Duration = Duration.ofSeconds(5),
)

data class LlmRoute(
    // provider 이름(gemini | claude)
    val provider: String,
    val model: String,
    val maxOutputTokens: Int,
    // 모델 내부 추론(thinking)에 쓸 토큰 예산. 0이면 끈다, null이면 provider 기본값.
    // Gemini 2.5+는 thinking 토큰이 maxOutputTokens에 포함되므로, 상한이 작은 용도에서는 0으로 꺼야 응답이 잘리지 않는다.
    val thinkingBudget: Int? = null,
)
