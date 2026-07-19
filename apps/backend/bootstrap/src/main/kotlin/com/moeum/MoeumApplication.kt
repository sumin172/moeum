package com.moeum

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

// UserDetailsServiceAutoConfiguration: JWT만으로 인증하고 UserDetailsService/AuthenticationProvider를
// 쓰지 않으므로, Spring Boot가 기본 생성하는 인메모리 사용자가 불필요해 명시적으로 제외 테스트
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
class MoeumApplication

fun main(args: Array<String>) {
    runApplication<MoeumApplication>(*args)
}
