package com.moeum.platform.llm.provider

import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRoute

// 각 provider의 API 호출과 종료 사유 해석을 담당한다. 라우팅·동시성 제한·관측은 RoutingLlmClient가 한다.
interface LlmProvider {
    // moeum.llm.routes.*.provider에 쓰는 이름
    val name: String

    // 공급사 이름(관측용)
    val vendor: String

    fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult

    // 이 공급자를 라우트로 쓸 때 필요한데 비어 있는 설정의 이름. 값은 반환하지 않는다.
    // 쓰지 않는 공급자는 설정이 없어도 기동하며, 사용하는 용도의 라우트만 기동 시 이 결과로 검사한다.
    fun missingSettings(): List<String>
}

data class LlmProviderResult(
    val text: String,
    // 공급사가 응답에 담아준 실제 모델 버전(없으면 요청한 모델)
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
    // 프롬프트 캐시로 처리된 입력 토큰(관측용)
    val cachedInputTokens: Int = 0,
    val finishReason: String? = null,
    // 완료되지 않은 출력의 오류 코드. 라우터가 실패 관측을 남기고 예외로 전환한다.
    val errorCode: String? = null,
)
