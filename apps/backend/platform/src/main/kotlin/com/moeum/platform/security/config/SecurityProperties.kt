package com.moeum.platform.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.security")
data class SecurityProperties(
    // 로컬 수동 테스트용 경로(/api/dev/**, /test-ui/**)를 인증 없이 연다. local 프로파일에서만 true로 둔다.
    val devPathsEnabled: Boolean = false,
)
