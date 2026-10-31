package com.moeum.platform.llm

import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import java.util.function.Supplier

// 용도를 쓰는 빈이 생성될 때 라우트·인증을 요구하면 컨텍스트 기동 자체가 실패하는지 확인한다.
class LlmRouteStartupTest {

    private class Provider(override val name: String, private val missing: List<String>) : LlmProvider {
        override val vendor = name
        override fun missingSettings() = missing
        override fun generate(route: LlmRoute, request: LlmRequest) = LlmProviderResult("ok", route.model, 1, 1)
    }

    // 업무 어댑터처럼 생성 시 자기 용도만 요구한다
    private class PurposeUser(client: LlmClient, purpose: String) { init { client.requireRoute(purpose) } }

    private fun start(routes: Map<String, LlmRoute>, providers: List<LlmProvider>, purpose: String): GenericApplicationContext =
        GenericApplicationContext().apply {
            registerBean(LlmClient::class.java, Supplier<LlmClient> { RoutingLlmClient(providers, LlmProperties(routes = routes)) { } })
            registerBean(PurposeUser::class.java, Supplier { PurposeUser(getBean(LlmClient::class.java), purpose) })
            refresh()
        }

    private val routes = mapOf(
        "conversation-response" to LlmRoute("cloudflare", "qwen", 150),
        "journal-generation" to LlmRoute("gemini", "flash", 2000),
    )

    @Test
    fun `사용 공급자의 설정 누락과 라우트 누락은 기동을 거부하고 미사용 공급자의 누락은 기동한다`() {
        val providers = listOf(
            Provider("cloudflare", listOf("moeum.cloudflare.account-id", "moeum.cloudflare.api-token")),
            Provider("gemini", emptyList()),
            Provider("claude", listOf("moeum.claude.api-key")),
        )

        start(routes, providers, "journal-generation").use { assertThat(it.isActive).isTrue() }
        assertThatThrownBy { start(routes, providers, "conversation-response") }
            .hasStackTraceContaining("moeum.cloudflare.account-id")
            .hasStackTraceContaining("moeum.cloudflare.api-token")
        assertThatThrownBy { start(routes - "journal-generation", providers, "journal-generation") }
            .hasStackTraceContaining("moeum.llm.routes.journal-generation 설정이 없습니다")
    }
}
