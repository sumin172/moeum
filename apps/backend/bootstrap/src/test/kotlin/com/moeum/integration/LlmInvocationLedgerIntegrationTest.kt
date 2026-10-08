package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmException
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRole
import com.moeum.platform.llm.ledger.LlmInvocationJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * LlmClient(라우팅) → provider → 호출 원장(platform.llm_invocations) 기록을 실제 DB로 검증한다.
 */
class LlmInvocationLedgerIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var llmClient: LlmClient

    @Autowired
    lateinit var invocationRepository: LlmInvocationJpaRepository

    private fun request(content: String, userId: UserId) =
        LlmRequest(
            purpose = "integration-test",
            systemPrompt = "system",
            messages = listOf(LlmMessage(LlmRole.USER, content)),
            promptVersion = "v-test",
            userId = userId,
        )

    @Test
    fun `성공한 호출은 사용자·용도·토큰과 함께 원장에 남는다`() {
        val userId = UserId.generate()

        val result = llmClient.generate(request("안녕", userId))

        val invocation = invocationRepository.findAll().single { it.userId == userId.value }
        assertThat(invocation.purpose).isEqualTo("integration-test")
        assertThat(invocation.provider).isEqualTo("fake-vendor")
        assertThat(invocation.promptVersion).isEqualTo("v-test")
        assertThat(invocation.generationId).isEqualTo(result.generationId)
        assertThat(invocation.inputTokens).isEqualTo(7)
        assertThat(invocation.outputTokens).isEqualTo(3)
        assertThat(invocation.succeeded).isTrue()
    }

    @Test
    fun `실패한 호출도 원장에 남는다`() {
        val userId = UserId.generate()

        assertThatThrownBy { llmClient.generate(request("fail", userId)) }.isInstanceOf(LlmException::class.java)

        val invocation = invocationRepository.findAll().single { it.userId == userId.value }
        assertThat(invocation.succeeded).isFalse()
        assertThat(invocation.errorCode).isEqualTo("LlmException")
    }
}
