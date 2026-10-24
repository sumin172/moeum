package com.moeum.capacity

import com.moeum.platform.llm.*
import com.moeum.platform.llm.provider.gemini.GeminiClientConfig
import com.moeum.platform.llm.provider.gemini.GeminiProperties
import com.moeum.platform.llm.provider.gemini.GeminiProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** 실제 원본·일기·인증 정보를 외부로 보내지 않는다. 성공해도 RPM/TPM 할당량을 증명하지 않는다. */
@Tag("provider-smoke")
@EnabledIfEnvironmentVariable(named="GEMINI_API_KEY", matches=".+")
class ProviderSmokeTest {
    @Test fun `configured Gemini route accepts text and journal JSON synthetic requests`() {
        val properties = GeminiProperties(apiKey=System.getenv("GEMINI_API_KEY"))
        val provider = GeminiProvider(GeminiClientConfig().geminiRestClient(properties),properties)
        val model = System.getenv("CAPACITY_SMOKE_GEMINI_MODEL") ?: "gemini-3.6-flash"
        val observations = mutableListOf<Map<String,Any?>>()
        val client = RoutingLlmClient(listOf(provider),LlmProperties(routes=mapOf(
            "smoke" to LlmRoute("gemini",model,300,thinkingBudget=0),
        ))
        ) { event ->
            observations += mapOf(
                "generationId" to event.generationId.toString(),
                "latencyMs" to event.latencyMs, "errorCode" to event.errorCode, "succeeded" to event.succeeded,
                "inputTokens" to event.inputTokens, "outputTokens" to event.outputTokens
            )
        }
        val mapper = JsonMapper.builder().build()
        val text = client.generate(LlmRequest("smoke","Return one short greeting.",
            listOf(LlmMessage(LlmRole.USER,"This is synthetic test data.")),"capacity-smoke-v1"))
        assertThat(text.text).isNotBlank()
        val journal = client.generate(LlmRequest("smoke","Return only a JSON object with string fields title and body. Keep both short.",
            listOf(LlmMessage(LlmRole.USER,"Synthetic diary: I walked in a park.")),"capacity-smoke-v1",LlmResponseFormat.JSON))
        val payload = mapper.readTree(journal.text)
        assertThat(payload["title"]?.isString).isTrue()
        assertThat(payload["body"]?.isString).isTrue()
        val file = Path.of("build","capacity","provider-smoke.json")
        Files.createDirectories(file.parent)
        Files.writeString(file,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(
            "at" to Instant.now().toString(),"configuredModel" to model,"returnedModel" to journal.model,
            "textValid" to true,"journalJsonValid" to true,"calls" to observations,"quotaVerified" to false,
        )))
    }
}
