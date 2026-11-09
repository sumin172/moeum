package com.moeum.platform.runtime

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
}
