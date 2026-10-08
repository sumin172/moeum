package com.moeum.platform.llm.provider

import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRoute

// provider 하나(Gemini, Claude)의 API 호출만 담당한다. 라우팅·동시성 제한·기록은 RoutingLlmClient가 한다.
interface LlmProvider {
    // moeum.llm.routes.*.provider에 쓰는 이름
    val name: String

    // 공급사 이름(원장·생성 메타데이터용)
    val vendor: String

    fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult
}

data class LlmProviderResult(
    val text: String,
    // 공급사가 응답에 담아준 실제 모델 버전(없으면 요청한 모델)
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    // 프롬프트 캐시로 처리된 입력 토큰(관측용)
    val cachedInputTokens: Int = 0,
    // 응답이 끝난 이유(STOP, MAX_TOKENS 등). 토큰 상한에 걸려 잘린 비율을 원장으로 관측하기 위함.
    val finishReason: String? = null,
)
