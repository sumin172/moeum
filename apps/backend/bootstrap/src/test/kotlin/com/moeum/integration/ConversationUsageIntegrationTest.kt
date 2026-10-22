package com.moeum.integration

import com.moeum.conversation.domain.ConversationUsageRepository
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationUsageIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var usage: ConversationUsageRepository
    private val minute = Instant.parse("2026-08-08T10:00:00Z")

    @Test fun `같은 분의 마지막 접수 슬롯은 동시 요청 중 하나만 차지한다`() {
        val user = UserId.generate()
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(20).use { pool ->
            val futures = (1..20).map { pool.submit<Boolean> { gate.await(); usage.consume(user,minute,1) } }
            gate.countDown()
            assertThat(futures.count { it.get(10,TimeUnit.SECONDS) }).isEqualTo(1)
        }
    }
    @Test fun `접수 속도 제한은 분이 바뀌면 해제된다`() {
        val user = UserId.generate()
        assertThat(usage.consume(user,minute,1)).isTrue()
        assertThat(usage.consume(user,minute,1)).isFalse()
        assertThat(usage.consume(user,minute.plusSeconds(60),1)).isTrue()
    }
}
