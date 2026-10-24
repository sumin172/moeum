package com.moeum.capacity

import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.infrastructure.ai.CONVERSATION_RESPONSE_PURPOSE
import com.moeum.conversation.infrastructure.ai.LlmConversationResponder
import com.moeum.identity.infrastructure.config.ConversationPlanProperties
import com.moeum.journal.infrastructure.ai.JOURNAL_GENERATION_PURPOSE
import com.moeum.journal.infrastructure.ai.LlmJournalGenerator
import com.moeum.journal.infrastructure.ai.JournalGenerationException
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmProperties
import com.moeum.platform.llm.RoutingLlmClient
import com.moeum.platform.llm.provider.gemini.GeminiClientConfig
import com.moeum.platform.llm.provider.gemini.GeminiProperties
import com.moeum.platform.llm.provider.gemini.GeminiProvider
import com.moeum.platform.observability.LlmCallEvent
import com.moeum.platform.observability.SafeFailure
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 지정된 합성 입력으로 실제 생성 경로를 검증한다. DB·스케줄러·자동 재시도는 실행하지 않는다. */
@Tag("provider-validation")
class JournalProviderValidationTest {
    @Test
    fun `configured AI paths process the selected synthetic input`() {
        val selected = System.getProperty("journalValidation.variant", "baseline")
        val variants = if (selected == "compare") listOf("conversation", "baseline") else listOf(selected)
        require(variants.all { it in setOf("conversation", "baseline") }) { "지원하는 variant: baseline, conversation, compare" }
        val selectedCase = System.getProperty("journalValidation.case", "representative")
        val cases = if (selectedCase == "quality") JournalValidationInputs.qualityCases else listOf(selectedCase)
        val repeats = System.getProperty("journalValidation.repeats", "1").toInt()
        require(repeats in 1..3)
        val results = cases.flatMap { case -> (1..repeats).flatMap { repeat -> variants.map { validate(it, case, repeat) } } }
        check(results.all { it }) { "공급자 검증 실패. build/capacity/journal-provider의 결과를 확인하세요." }
    }

    private fun validate(variant: String, case: String, repeat: Int): Boolean {
        val environment = StandardEnvironment().apply {
            val loader = YamlPropertySourceLoader()
            // 환경변수·JVM 속성 다음으로 local, worker, 공통 설정 순서로 우선한다.
            listOf("application-local.yml", "application-journal-worker.yml", "application.yml").forEach { name ->
                val resource = ClassPathResource(name)
                if (resource.exists()) loader.load(name, resource).forEach(propertySources::addLast)
            }
        }
        val binder = Binder.get(environment)
        // 인증 설정 객체와 바인딩 예외는 출력하거나 결과 파일에 직렬화하지 않는다.
        val gemini = try {
            binder.bind("moeum.gemini", Bindable.of(GeminiProperties::class.java)).get()
        } catch (_: Exception) {
            error("moeum.gemini 설정을 확인하세요. 인증 값은 출력하지 않습니다.")
        }
        require(gemini.apiKey.isNotBlank()) { "Gemini API 키가 필요합니다" }
        val llm = binder.bind("moeum.llm", Bindable.of(LlmProperties::class.java)).get()
        val purpose = if (variant == "conversation") CONVERSATION_RESPONSE_PURPOSE else JOURNAL_GENERATION_PURPOSE
        val configuredRoute = llm.routes.getValue(purpose)
        val model = System.getProperty("journalValidation.model") ?: configuredRoute.model
        val route = configuredRoute.copy(model = model)
        require(route.provider == "gemini") { "이 검증은 설정된 Gemini 경로만 지원합니다" }
        val plans = binder.bind("moeum.identity.conversation", Bindable.of(ConversationPlanProperties::class.java)).get()
        val limits = binder.bind("moeum.conversation.limits", Bindable.of(ConversationLimitProperties::class.java)).get()
        val messages = JournalValidationInputs.create(case, plans.freeRecordLimit, plans.premiumRecordLimit, limits.maxMessageLength)
        val events = mutableListOf<LlmCallEvent>()
        val mapper = JsonMapper.builder().build()
        var httpFailure: Map<String, Any?>? = null
        val restClient = GeminiClientConfig().geminiRestClient(gemini).mutate()
            .defaultStatusHandler({ it.isError }) { request, response ->
                try {
                    DefaultResponseErrorHandler().handleError(request.uri, request.method, response)
                } catch (error: RestClientResponseException) {
                    httpFailure = summarizeProviderFailure(error, mapper)
                    throw error
                }
            }.build()
        val provider = GeminiProvider(restClient, gemini)
        val client = RoutingLlmClient(listOf(provider), llm.copy(routes = mapOf(purpose to route))) { events += it }
        val started = System.nanoTime()
        val generated = runCatching {
            if (variant == "conversation") {
                val user = UserId.generate()
                val record = RecordDayId.generate()
                var source = 0L
                val context = messages.map { message ->
                    if (message.role == "USER") {
                        Message.userMessage(MessageId.generate(), user, message.content, message.occurredAt, record,
                            ++source, UUID.randomUUID(), message.occurredAt)
                    } else Message.assistantMessage(MessageId.generate(), user, message.content, message.occurredAt, record, source)
                }
                mapOf("content" to LlmConversationResponder(client, limits).respond(context).content)
            } else {
                val journal = LlmJournalGenerator(client, mapper).generate(LocalDate.of(2026, 10, 23), messages)
                mapOf("title" to journal.title, "body" to journal.body)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val event = events.singleOrNull()
        val hasProviderResult = event != null && (event.succeeded || event.errorCode in setOf(
            "OUTPUT_TRUNCATED", "OUTPUT_BLOCKED", "UNEXPECTED_FINISH_REASON", "EMPTY_CONTENT"))
        val error = generated.exceptionOrNull()
        val output = generated.getOrNull()
        val inputHash = MessageDigest.getInstance("SHA-256")
            .digest(messages.joinToString("\n") { "${it.occurredAt} ${it.role}: ${it.content}" }.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val report = linkedMapOf(
            "at" to Instant.now().toString(), "case" to case, "inputSha256" to inputHash,
            "variant" to variant, "purpose" to purpose,
            "repeat" to repeat,
            "configuredModel" to configuredRoute.model, "requestedModel" to route.model,
            "observedModel" to event?.model?.takeIf { hasProviderResult }, "promptVersion" to event?.promptVersion,
            "maxOutputTokens" to route.maxOutputTokens, "thinkingBudget" to route.thinkingBudget,
            "connectionTimeoutMs" to gemini.connectionTimeoutMs, "readTimeoutMs" to gemini.readTimeoutMs,
            "userMessages" to messages.count { it.role == "USER" }, "assistantMessages" to messages.count { it.role == "ASSISTANT" },
            "userChars" to messages.filter { it.role == "USER" }.sumOf { it.content.length },
            "assistantChars" to messages.filter { it.role == "ASSISTANT" }.sumOf { it.content.length },
            "generationCalls" to events.size, "generated" to generated.isSuccess, "latencyMs" to elapsedMs,
            "finishReason" to event?.finishReason, "errorCode" to error?.let(SafeFailure::code),
            // 생성기가 작성한 고정 진단만 보존한다. 공급자 예외 본문·원문은 포함하지 않는다.
            "journalValidationFailure" to (error as? JournalGenerationException)?.message,
            "httpFailure" to httpFailure,
            "inputTokens" to event?.inputTokens?.takeIf { hasProviderResult },
            "outputTokens" to event?.outputTokens?.takeIf { hasProviderResult },
            "cachedInputTokens" to event?.cachedInputTokens?.takeIf { hasProviderResult },
            "expectedFacts" to if (variant == "conversation") null else JournalValidationInputs.expectedFacts(case),
            "journal" to output?.takeUnless { variant == "conversation" },
            "conversation" to output?.takeIf { variant == "conversation" },
        )
        val file = Path.of("build", "capacity", "journal-provider", "$variant-$case-${UUID.randomUUID()}.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        println("Provider validation: variant=$variant, case=$case, generated=${generated.isSuccess}, errorCode=${error?.let(SafeFailure::code)}, report=$file")
        check(events.size == 1) { "실행당 공급자 호출은 한 번이어야 합니다" }
        return generated.isSuccess
    }
}

/** 오류 본문은 내보내지 않고 프로토콜·공급자 오류 분류만 보존한다. */
internal fun summarizeProviderFailure(error: RestClientResponseException, mapper: JsonMapper): Map<String, Any?> {
    val root = runCatching { mapper.readTree(error.responseBodyAsByteArray) }.getOrNull()?.get("error")
    val message = root?.get("message")?.asString().orEmpty().lowercase()
    val diagnosis = when {
        root == null -> "NON_API_ERROR_BODY"
        "free tier" in message -> "FREE_TIER_AVAILABILITY"
        listOf("overload", "high demand", "capacity", "busy").any { it in message } -> "MODEL_CAPACITY"
        listOf("not enabled", "disabled").any { it in message } -> "SERVICE_DISABLED"
        listOf("not supported", "not available", "not found").any { it in message } -> "MODEL_UNAVAILABLE"
        "unavailable" in message -> "SERVICE_UNAVAILABLE"
        else -> "UNCLASSIFIED_API_ERROR"
    }
    return mapOf(
        "status" to error.statusCode.value(),
        "apiStatus" to root?.get("status")?.asString()?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) },
        "diagnosis" to diagnosis,
        "contentType" to error.responseHeaders?.contentType?.let { "${it.type}/${it.subtype}" },
        "retryAfterSeconds" to error.responseHeaders?.getFirst("Retry-After")?.toLongOrNull(),
    )
}
