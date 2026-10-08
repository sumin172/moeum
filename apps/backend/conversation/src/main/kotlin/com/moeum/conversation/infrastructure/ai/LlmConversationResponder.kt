package com.moeum.conversation.infrastructure.ai

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// moeum.llm.routes.conversation-response로 provider·모델·토큰 상한이 정해진다.
const val CONVERSATION_RESPONSE_PURPOSE = "conversation-response"

// 프롬프트를 바꾸면 버전을 올린다(생성 메타데이터·호출 원장에 남아 품질 비교의 기준이 된다).
// v2: 응답 토큰 상한(150)에 맞춰 짧게 답하도록 지시 추가
private const val PROMPT_VERSION = "v2"

// 저널링 앱의 대화 상대로서 소재를 유도하는 시스템 지시. 프롬프트 기반이라 100% 강제는 아니고 최소 가드레일 목적.
private const val SYSTEM_PROMPT =
    "당신은 사용자의 일상 기록을 돕는 다정한 대화 상대입니다. 사용자가 겪은 하루에 공감하며 자연스럽게 응답하세요. " +
        "답변은 두세 문장 이내로 짧게 하고, 필요하면 하루를 더 떠올리게 하는 질문을 하나 덧붙이세요. " +
        "코드 작성, 시사·정치 논쟁, 전문 의료·법률 상담처럼 일상 대화와 무관한 요청이 오면 답변이 어려움을 정중히 안내하고 일상 주제로 되돌리세요."

@Component
class LlmConversationResponder(
    private val llmClient: LlmClient,
    private val limits: ConversationLimitProperties,
) : ConversationResponder {

    private val log = LoggerFactory.getLogger(LlmConversationResponder::class.java)

    override fun respond(userId: UserId, context: List<Message>): ConversationResponse {
        val fitted = fitToBudget(context, limits.contextCharBudget)
        if (fitted.size < context.size) {
            log.info(
                "AI 컨텍스트가 예산을 넘어 최근 대화만 전달: userId={}, total={}, sent={}, budgetChars={}",
                userId.value, context.size, fitted.size, limits.contextCharBudget,
            )
        }
        val result = llmClient.generate(
            LlmRequest(
                purpose = CONVERSATION_RESPONSE_PURPOSE,
                systemPrompt = SYSTEM_PROMPT,
                messages = fitted.map { LlmMessage(role = it.role.toLlmRole(), content = it.content) },
                promptVersion = PROMPT_VERSION,
                userId = userId,
            ),
        )
        return ConversationResponse(
            generationId = result.generationId,
            content = result.text,
            model = result.model,
            provider = result.provider,
            promptVersion = result.promptVersion,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
        )
    }
}

// 최근 메시지부터 거꾸로 담아 예산(글자 수) 안에서 가장 긴 최근 구간을 남긴다. 마지막(가장 최근) 메시지는 예산을 넘어도
// 항상 포함한다(메시지 길이 상한이 예산보다 작아 정상 설정에서는 일어나지 않는다).
// 잘린 앞부분이 AI 응답으로 시작하면 그것도 뺀다 — 대화는 사용자 발화로 시작해야 자연스럽다.
internal fun fitToBudget(context: List<Message>, budgetChars: Int): List<Message> {
    var used = 0
    val kept = context.asReversed().takeWhile { message ->
        used += message.content.length
        used <= budgetChars
    }.ifEmpty { context.takeLast(1) }.asReversed()
    return if (kept.size == context.size) kept else kept.dropWhile { it.role == MessageRole.ASSISTANT }.ifEmpty { kept }
}

private fun MessageRole.toLlmRole(): LlmRole =
    when (this) {
        MessageRole.USER -> LlmRole.USER
        MessageRole.ASSISTANT -> LlmRole.ASSISTANT
    }
