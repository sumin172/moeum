package com.moeum.conversation.infrastructure.config

import com.moeum.platform.job.JobPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// AI 응답 생성 작업의 재시도/리스 정책. 수치는 잠정값 — 실사용 실패 패턴을 본 뒤 조정한다.
@ConfigurationProperties(prefix = "moeum.conversation.response-job")
data class ResponseJobProperties(
    val maxAttempts: Int = 3,
    val backoff: List<Duration> = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
    // Gemini read timeout(15초)보다 넉넉하게
    val lease: Duration = Duration.ofMinutes(2),
    // poller가 한 번에 가져가는 최대 작업 수
    val pollBatchSize: Int = 20,
) {
    fun toPolicy(): JobPolicy = JobPolicy(maxAttempts = maxAttempts, backoff = backoff, lease = lease)
}
