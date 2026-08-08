package com.moeum

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

// UserDetailsServiceAutoConfiguration: JWT만으로 인증하고 UserDetailsService/AuthenticationProvider를
// 쓰지 않으므로, Spring Boot가 기본 생성하는 인메모리 사용자가 불필요해 명시적으로 제외
// EnableAsync: 메시지 저장 후 AI 응답 생성을 비동기로 트리거하기 위함(GenerateConversationResponseService)
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableAsync
class MoeumApplication

fun main(args: Array<String>) {
    runApplication<MoeumApplication>(*args)
}
