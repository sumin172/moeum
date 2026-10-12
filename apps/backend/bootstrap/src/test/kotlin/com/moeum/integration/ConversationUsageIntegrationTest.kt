package com.moeum.integration

import com.moeum.conversation.domain.ConversationUsageRepository
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConversationUsageIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var usage: ConversationUsageRepository
    private val date = LocalDate.of(2026,8,8)
    private val minute = Instant.parse("2026-08-08T10:00:00Z")

    @Test fun `남은 횟수 하나에 동시 접수하면 하나만 차감된다`() {
        val user = UserId.generate()
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(20).use { pool ->
            val futures = (1..20).map { pool.submit<Boolean> { gate.await(); usage.consume(user,date,minute,1,60) } }
            gate.countDown()
            assertThat(futures.count { it.get(10,TimeUnit.SECONDS) }).isEqualTo(1)
        }
        assertThat(usage.find(user)!!.used).isEqualTo(1)
    }
    @Test fun `접수 속도 제한은 분이 바뀌면 해제되고 일일 사용량은 유지한다`() {
        val user = UserId.generate()
        assertThat(usage.consume(user,date,minute,20,1)).isTrue()
        assertThat(usage.consume(user,date,minute,20,1)).isFalse()
        assertThat(usage.consume(user,date,minute.plusSeconds(60),20,1)).isTrue()
        assertThat(usage.find(user)!!.used).isEqualTo(2)
    }
    @Test fun `새 UTC 기간은 한 행을 갱신해 사용량을 초기화한다`() {
        val user = UserId.generate()
        usage.consume(user,date,minute,1,60)
        assertThat(usage.consume(user,date.plusDays(1),minute.plusSeconds(86400),1,60)).isTrue()
        assertThat(usage.find(user)!!.used).isEqualTo(1)
        assertThat(usage.find(user)!!.date).isEqualTo(date.plusDays(1))
    }
}
