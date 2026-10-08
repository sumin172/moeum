package com.moeum.integration.support

import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult

// 실제 provider를 부르지 않고 RoutingLlmClient → 원장 기록 경로를 검증하기 위한 provider.
// 메시지 내용이 "fail"이면 실패한다. moeum.llm.routes.integration-test가 이 provider를 쓴다.
class FakeLlmProvider : LlmProvider {
    override val name = "fake"
    override val vendor = "fake-vendor"

    override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
        if (request.messages.any { it.content == "fail" }) throw LlmException("테스트용 실패")
        // 일기 생성은 JSON 응답을 기대한다
        val text = if (request.purpose == "journal-generation") """{"title":"테스트 일기","body":"오늘의 기록"}""" else "ok"
        return LlmProviderResult(text = text, model = route.model, inputTokens = 7, outputTokens = 3, finishReason = "STOP")
    }
}
