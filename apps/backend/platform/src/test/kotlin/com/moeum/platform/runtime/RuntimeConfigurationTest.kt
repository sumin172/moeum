package com.moeum.platform.runtime

import com.moeum.platform.llm.LlmProperties
import com.moeum.platform.llm.LlmRoute
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class RuntimeConfigurationTest {
    @Test
    fun `운영 역할 혼합과 HTTP 워커를 기동 전에 거부한다`() {
        assertThatThrownBy {
            RuntimeConfiguration(RuntimeProperties(roles = ExecutionRole.entries.toSet()), MockEnvironment())
        }.hasMessageContaining("local")
        assertThatThrownBy {
            RuntimeConfiguration(RuntimeProperties(roles = setOf(ExecutionRole.JOURNAL_WORKER)), MockEnvironment())
        }.hasMessageContaining("web-application-type")
        assertThatThrownBy {
            RuntimeConfiguration(
                RuntimeProperties(roles = setOf(ExecutionRole.JOURNAL_WORKER)),
                MockEnvironment().withProperty("spring.main.web-application-type", "reactive"),
            )
        }.hasMessageContaining("web-application-type")
        assertThatThrownBy {
            RuntimeConfiguration(RuntimeProperties(), MockEnvironment().apply { setActiveProfiles("api", "journal-worker") })
        }.hasMessageContaining("중복")
    }

    @Test
    fun `역할별 실행 슬롯 합산과 LLM 예산을 검증한다`() {
        val llm = LlmProperties(
            routes = mapOf(
                "conversation-response" to LlmRoute("fake", "test", 100),
                "journal-generation" to LlmRoute("fake", "test", 100),
            ),
            maxConcurrentCalls = mapOf("fake" to 8),
        )
        WorkerBudgetConfiguration(RuntimeProperties(roles = setOf(ExecutionRole.CONVERSATION_WORKER)), llm)
        assertThatThrownBy {
            WorkerBudgetConfiguration(RuntimeProperties(roles = ExecutionRole.entries.toSet()), llm)
        }.hasMessageContaining("실행 슬롯 합계")
        assertThatThrownBy { LlmProperties(maxConcurrentCalls = mapOf("fake" to 0)) }.hasMessageContaining("양수")
    }
}
