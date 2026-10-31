package com.moeum.capacity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderValidationInputsTest {
    @Test
    fun `최대 입력 사례는 지정한 USER 한도와 길이를 정확히 채운다`() {
        listOf("free-max" to 20, "premium-max" to 100).forEach { (case, count) ->
            val input = ProviderValidationInputs.create(case, 20, 100, 4000)
            val users = input.filter { it.role == "USER" }
            assertThat(users).hasSize(count)
            assertThat(users).allSatisfy { assertThat(it.content).hasSize(4000) }
            assertThat(input.count { it.role == "ASSISTANT" }).isEqualTo(count - 1)
            assertThat(input.last().role).isEqualTo("USER")
            assertThat(users[1].content).contains("한강이 아니라 서울숲")
            assertThat(users[count / 2 + 1].content).contains("아니라고")
            assertThat(users.last().content).contains("버섯 파스타")
        }
    }

    @Test
    fun `합성 입력은 재실행해도 같은 내용과 시각을 사용한다`() {
        assertThat(ProviderValidationInputs.create("premium-max", 20, 100, 4000))
            .isEqualTo(ProviderValidationInputs.create("premium-max", 20, 100, 4000))
    }
}
