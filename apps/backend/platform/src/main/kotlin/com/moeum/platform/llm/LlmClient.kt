package com.moeum.platform.llm

import java.util.UUID

// 업무 모듈이 LLM을 부르는 유일한 진입점. platform은 "어떻게 부르는지"(provider, 모델, 토큰 상한, 장애 격리,
// 호출 관측)만 알고, "무엇을 시킬지"(프롬프트, 응답 해석)는 각 업무 모듈이 소유한다.
interface LlmClient {
    // 실패하면 원문을 포함하지 않는 LlmException. 진단은 bounded 관측 경로로 보낸다.
    fun generate(request: LlmRequest): LlmResult
}

data class LlmRequest(
    // 용도. moeum.llm.routes.<purpose>로 provider·모델·토큰 상한이 정해지고, 관측에도 이 이름으로 남는다.
    val purpose: String,
    val systemPrompt: String,
    val messages: List<LlmMessage>,
    // 프롬프트를 소유한 모듈이 관리하는 버전. 관측과 생성 메타데이터에 남긴다.
    val promptVersion: String,
    val responseFormat: LlmResponseFormat = LlmResponseFormat.TEXT,
)

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val content: String)

enum class LlmResponseFormat {
    TEXT,

    // JSON 객체만 응답하게 한다. provider가 강제 옵션을 지원하면 쓰고(Gemini), 아니면 프롬프트에 맡긴다(Claude).
    JSON,
}

data class LlmResult(
    val generationId: UUID,
    val text: String,
    val model: String,
    val promptVersion: String,
)

class LlmException(message: String, cause: Throwable? = null, val errorCode: String? = null) : RuntimeException(message, cause)
