package com.moeum.platform.job

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 제품 확정값이 아닌 운영 기본안. 작업만 정리하고 원본·결과는 보존한다. */
@ConfigurationProperties("moeum.jobs.retention")
data class CompletedJobRetentionProperties(
    val completedAge: Duration = Duration.ofDays(30),
    val batchSize: Int = 100,
) {
    init {
        require(completedAge >= Duration.ofDays(1))
        require(batchSize in 1..1000)
    }
}
