package com.moeum.platform.runtime

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("moeum.runtime")
data class RuntimeProperties(
    val roles: Set<ExecutionRole> = setOf(ExecutionRole.API),
    val conversationConcurrency: Int = 8,
    val journalConcurrency: Int = 2,
    val shutdownTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        require(roles.isNotEmpty()) { "실행 역할이 필요합니다" }
        require(conversationConcurrency > 0 && journalConcurrency > 0) { "실행 슬롯은 양수여야 합니다" }
        require(!shutdownTimeout.isNegative) { "종료 대기 시간은 음수일 수 없습니다" }
    }
}
