package com.moeum.platform.runtime

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** 잘못된 역할 조합을 기동 시점에 거부한다. */
@Configuration(proxyBeanMethods = false)
class RuntimeConfiguration(properties: RuntimeProperties, environment: Environment) {
    init {
        val profiles = environment.activeProfiles.toSet()
        require(profiles.intersect(setOf("api", "conversation-worker", "journal-worker")).size <= 1) {
            "하나의 프로세스에서 역할 프로필을 중복 지정할 수 없습니다"
        }
        require(properties.roles.size == 1 || "local" in profiles) {
            "복수 실행 역할은 local 개발/테스트에서만 허용합니다"
        }
        val webType = environment.getProperty("spring.main.web-application-type", "servlet")
        val expectedWebType = if (ExecutionRole.API in properties.roles) "servlet" else "none"
        require(webType.equals(expectedWebType, ignoreCase = true)) {
            "API는 servlet, 워커는 none web-application-type을 사용해야 합니다"
        }
        LoggerFactory.getLogger(RuntimeConfiguration::class.java).info("실행 역할: roles={}, webType={}", properties.roles, webType)
    }
}
