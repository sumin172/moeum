package com.moeum.platform.job

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 종료된 작업만 정리하고 원본·결과는 보존한다. */
@ConfigurationProperties("moeum.jobs.retention")
data class JobRetentionProperties(
    val terminalAge: Duration = Duration.ofDays(30),
    val batchSize: Int = 100,
) {
    init {
        require(terminalAge >= Duration.ofDays(1))
        require(batchSize in 1..1000)
    }
}
