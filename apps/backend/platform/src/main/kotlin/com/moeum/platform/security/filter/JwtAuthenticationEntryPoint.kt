package com.moeum.platform.security.filter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

@Component
class JwtAuthenticationEntryPoint(
    private val jsonMapper: JsonMapper,
) : AuthenticationEntryPoint {

    // 실패 사유(만료/서명불일치/누락)는 클라이언트에 노출하지 않는다 — 상세 사유는 JwtAuthenticationFilter가 로그로 남긴다.
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        jsonMapper.writeValue(response.writer, mapOf("code" to "UNAUTHORIZED", "message" to "인증이 필요합니다"))
    }
}
