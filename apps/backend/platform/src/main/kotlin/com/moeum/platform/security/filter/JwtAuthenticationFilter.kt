package com.moeum.platform.security.filter

import com.moeum.platform.security.jwt.JwtProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")

        // 파싱 실패 시에도 그냥 통과시킨다 — 인증 여부 판단은 authorizeHttpRequests가 담당한다.
        // 실패 사유는 로그로만 남기고 클라이언트 응답(401)에는 노출하지 않는다.
        if (token != null) {
            runCatching { jwtProvider.parse(token) }
                .onSuccess { claims ->
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(claims.userId, null, emptyList())
                }
                .onFailure { e ->
                    log.debug("JWT 인증 실패: {}", e.message)
                }
        }

        filterChain.doFilter(request, response)
    }
}
