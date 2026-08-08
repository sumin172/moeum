package com.moeum.integration

import com.moeum.conversation.domain.AiUsageRepository
import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ON CONFLICT ... RETURNING 원자성은 fake로 검증할 수 없다 — 실제 Postgres로 진짜 계약을 확인한다.
 */
class AiUsageRepositoryIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var aiUsageRepository: AiUsageRepository

    @Test
    fun `연속 호출하면 1부터 순서대로 증가한다`() {
        val userId = UserId.generate()
        val date = LocalDate.of(2026, 8, 8)

        assertThat(aiUsageRepository.recordAttempt(userId, date)).isEqualTo(1)
        assertThat(aiUsageRepository.recordAttempt(userId, date)).isEqualTo(2)
        assertThat(aiUsageRepository.recordAttempt(userId, date)).isEqualTo(3)
    }

    @Test
    fun `같은 유저 날짜에 동시에 N번 호출해도 유실이나 중복 없이 정확히 1부터 N까지 나온다`() {
        val userId = UserId.generate()
        val date = LocalDate.of(2026, 8, 8)
        val concurrency = 20
        val executor = Executors.newFixedThreadPool(concurrency)

        val results = try {
            val futures = (1..concurrency).map {
                executor.submit<Int> { aiUsageRepository.recordAttempt(userId, date) }
            }
            futures.map { it.get() }
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }

        assertThat(results.sorted()).isEqualTo((1..concurrency).toList())
    }
}
