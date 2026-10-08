package com.moeum

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

// UserDetailsServiceAutoConfiguration: JWT만으로 인증하고 UserDetailsService/AuthenticationProvider를
// 쓰지 않으므로, Spring Boot가 기본 생성하는 인메모리 사용자가 불필요해 명시적으로 제외
// EnableAsync: 메시지 저장·재시도 요청 직후 AI 응답 작업을 비동기로 바로 실행하기 위함(ResponseJobExecutor)
// EnableScheduling: AI 응답 작업 poller와 일기 Planner/Executor(moeum.worker.enabled=true일 때만 등록)
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
class MoeumApplication

fun main(args: Array<String>) {
    runApplication<MoeumApplication>(*args)
}
