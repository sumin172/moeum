package com.moeum.conversation.infrastructure.ai

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole

import com.moeum.conversation.domain.ConversationResponder
import com.moeum.conversation.domain.ConversationResponse
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageRole
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// moeum.llm.routes.conversation-response로 provider·모델·토큰 상한이 정해진다.
const val CONVERSATION_RESPONSE_PURPOSE = "conversation-response"

// 프롬프트를 바꾸면 요청의 버전을 올린다.
private const val PROMPT_VERSION = "v3"

// 저널링 앱의 대화 상대로서 소재를 유도하는 시스템 지시. 프롬프트 기반이라 100% 강제는 아니고 최소 가드레일 목적.
private const val SYSTEM_PROMPT =
    "당신은 사용자의 일상 기록을 돕는 다정한 대화 상대입니다. 사용자가 겪은 하루에 공감하며 자연스럽게 응답하세요. " +
        "답변은 두세 문장 이내로 짧게 하고, 필요하면 하루를 더 떠올리게 하는 질문을 하나 덧붙이세요. " +
        "사용자가 직접 말한 사실과 감정만 반영하고, 나중에 정정하거나 부정한 내용을 우선하세요. " +
        "계획 취소나 분실만으로 속상함·아쉬움·힘든 하루를 추측하지 마세요. " +
        "훈계·평가·충고를 하지 말고 사용자의 말을 그대로 길게 반복하거나 일기 문체로 대신 서술하지 마세요. " +
        "이미 답한 내용을 다시 묻지 말고, 현재 상황과 관련된 질문이 없으면 질문을 생략하세요. " +
        "한국어의 자연스러운 해요체를 일관되게 사용하세요. " +
        "코드 작성, 시사·정치 논쟁, 전문 의료·법률 상담처럼 일상 대화와 무관한 요청이 오면 답변이 어려움을 정중히 안내하고 일상 주제로 되돌리세요."

@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
@Component
class LlmConversationResponder(
    private val llmClient: LlmClient,
    private val limits: ConversationLimitProperties,
) : ConversationResponder {

    private val log = LoggerFactory.getLogger(LlmConversationResponder::class.java)

    // 대화 워커에만 등록되므로 대화 경로의 라우트와 인증만 기동 시 확인된다.
    init { llmClient.requireRoute(CONVERSATION_RESPONSE_PURPOSE) }

    override fun respond(history: List<Message>, targets: List<Message>): ConversationResponse {
        // 답변 대상은 그대로 보내고 남는 예산으로 과거 문맥만 줄인다.
        val remaining = limits.contextCharBudget - targets.sumOf { it.content.length }
        val fitted = fitHistory(history, remaining)
        if (fitted.size < history.size) {
            log.info(
                "AI 컨텍스트가 예산을 넘어 최근 대화만 전달: history={}, sent={}, targets={}, budgetChars={}",
                history.size, fitted.size, targets.size, limits.contextCharBudget,
            )
        }
        val result = llmClient.generate(
            LlmRequest(
                purpose = CONVERSATION_RESPONSE_PURPOSE,
                systemPrompt = SYSTEM_PROMPT,
                messages = (fitted + targets).map { LlmMessage(role = it.role.toLlmRole(), content = it.content) },
                promptVersion = PROMPT_VERSION,
            ),
        )
        return ConversationResponse(content = result.text)
    }
}

// 최근 메시지부터 거꾸로 담아 예산(글자 수) 안에서 가장 긴 최근 구간을 남긴다.
// 잘린 앞부분이 AI 응답으로 시작하면 그것도 뺀다 — 대화는 사용자 발화로 시작해야 자연스럽다.
internal fun fitHistory(history: List<Message>, budgetChars: Int): List<Message> {
    var used = 0
    val kept = history.asReversed().takeWhile { message ->
        used += message.content.length
        used <= budgetChars
    }.asReversed()
    return if (kept.size == history.size) kept else kept.dropWhile { it.role == MessageRole.ASSISTANT }
}

private fun MessageRole.toLlmRole(): LlmRole =
    when (this) {
        MessageRole.USER -> LlmRole.USER
        MessageRole.ASSISTANT -> LlmRole.ASSISTANT
    }
