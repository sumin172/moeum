package com.moeum.conversation.infrastructure.config

import com.moeum.platform.job.JobPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// AI 응답 생성 작업의 재시도/리스 정책. 수치는 잠정값 — 실사용 실패 패턴을 본 뒤 조정한다.
@ConfigurationProperties(prefix = "moeum.conversation.response-job")
data class ResponseJobProperties(
    val maxAttempts: Int = 3,
    val backoff: List<Duration> = listOf(Duration.ofSeconds(10), Duration.ofSeconds(60)),
    // provider read timeout(60초)보다 넉넉하게
    val lease: Duration = Duration.ofMinutes(2),
    // 실행 슬롯을 미리 확보하고 이 수 이하를 한 트랜잭션에서 선점한다.
    val claimBatchSize: Int = 32,
    val pollInterval: Duration = Duration.ofSeconds(1),
) {
    init {
        require(claimBatchSize in 1..64)
        require(!pollInterval.isNegative && !pollInterval.isZero)
    }
    fun toPolicy(): JobPolicy = JobPolicy(maxAttempts = maxAttempts, backoff = backoff, lease = lease)
}
