package com.moeum.journal.infrastructure.config

import com.moeum.platform.job.JobPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 일기 생성 작업의 재시도/리스 정책. 일기는 지연이 허용되므로 대화 응답보다 길게 기다리며 더 여러 번 시도한다.
// 수치는 잠정값 — 실사용 실패 패턴을 본 뒤 조정한다.
@ConfigurationProperties(prefix = "moeum.journal.generation-job")
data class GenerationJobProperties(
    val maxAttempts: Int = 5,
    val backoff: List<Duration> = listOf(
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
        Duration.ofHours(2),
    ),
    val lease: Duration = Duration.ofMinutes(5),
    val maxTotalAttempts: Int = maxAttempts + 2,
    val manualRetryCooldown: Duration = Duration.ofMinutes(1),
    val rateLimitBackoff: Duration = Duration.ofMinutes(5),
) {
    fun toPolicy(): JobPolicy = JobPolicy(maxAttempts = maxAttempts, backoff = backoff, lease = lease,
        maxTotalAttempts = maxTotalAttempts, manualRetryCooldown = manualRetryCooldown, rateLimitBackoff = rateLimitBackoff)
}
