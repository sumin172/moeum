package com.moeum.conversation.infrastructure.config

import com.moeum.platform.job.JobPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// AI 응답 생성 작업의 재시도/리스 정책. 수치는 잠정값 — 실사용 실패 패턴을 본 뒤 조정한다.
@ConfigurationProperties(prefix = "moeum.conversation.response-job")
data class ResponseJobProperties(
    val maxAttempts: Int = 3,
    val backoff: List<Duration> = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
    // 실행 한 번의 상한. 넘긴 작업은 FAILED로 정리하므로 provider read timeout(60초)보다 넉넉하게 둔다.
    val lease: Duration = Duration.ofMinutes(2),
    // 최종 실패 뒤 새 메시지로 재개하기 전 최소 대기
    val reopenCooldown: Duration = Duration.ofMinutes(1),
    val rateLimitBackoff: Duration = Duration.ofMinutes(1),
) {
    fun toPolicy(): JobPolicy = JobPolicy(maxAttempts = maxAttempts, backoff = backoff, lease = lease,
        reopenCooldown = reopenCooldown, rateLimitBackoff = rateLimitBackoff)
}
