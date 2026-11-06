package com.moeum.journal.infrastructure.config

import com.moeum.platform.job.JobPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 일기 생성 작업의 재시도/리스 정책. 일기는 지연이 허용되므로 대화 응답보다 긴 간격으로 더 여러 번 재시도한다.
// 리스는 실행 한 번의 상한이다. 넘긴 작업은 FAILED로 정리하므로 provider read timeout(60초)보다 넉넉하게 둔다.
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
    val lease: Duration = Duration.ofMinutes(2),
    val rateLimitBackoff: Duration = Duration.ofMinutes(5),
) {
    fun toPolicy(): JobPolicy = JobPolicy(maxAttempts = maxAttempts, backoff = backoff, lease = lease,
        rateLimitBackoff = rateLimitBackoff)
}
