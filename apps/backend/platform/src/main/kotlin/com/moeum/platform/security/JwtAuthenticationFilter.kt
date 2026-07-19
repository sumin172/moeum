package com.moeum.platform.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(
    private val jwtProvider: JwtProvider,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")

        // 파싱 실패 시에도 그냥 통과시킨다 — 인증 여부 판단은 authorizeHttpRequests가 담당한다.
        if (token != null) {
            runCatching { jwtProvider.parse(token) }
                .onSuccess { claims ->
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(claims.userId, null, emptyList())
                }
        }

        filterChain.doFilter(request, response)
    }
}
