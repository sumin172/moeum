package com.moeum.identity.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "moeum.auth.session")
data class AuthSessionProperties(
    // refresh token 유효 기간. 로그인·refresh할 때마다 이 기간으로 다시 연장된다.
    val refreshTokenTtl: Duration = Duration.ofDays(30),
    // 교체된 직전 토큰이 이 시간 안에 다시 들어오면 재사용(탈취)이 아니라 동시 요청으로 본다.
    val rotationGrace: Duration = Duration.ofSeconds(30),
)
