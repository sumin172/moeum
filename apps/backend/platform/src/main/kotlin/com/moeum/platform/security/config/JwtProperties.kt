package com.moeum.platform.security.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "moeum.jwt")
data class JwtProperties(
    val secret: String,
    // access token 수명. 짧게 두고 refresh token(identity의 AuthSession)으로 갱신한다 —
    // 서버가 상태 없이 검증하는 토큰이라, 세션 폐기(로그아웃·탈퇴)가 반영되기까지 최대 이 시간이 걸린다.
    val expirationSeconds: Long = 900,
)
