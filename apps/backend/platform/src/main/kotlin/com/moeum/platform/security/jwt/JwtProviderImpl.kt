package com.moeum.platform.security.jwt

import com.moeum.kernel.UserId
import com.moeum.platform.security.config.JwtProperties
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Component
import java.util.Date

@Component
class JwtProviderImpl(
    jwtProperties: JwtProperties,
) : JwtProvider {

    private val signingKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    override fun issue(claims: JwtClaims): String =
        Jwts.builder()
            .subject(claims.userId.value.toString())
            .issuedAt(Date.from(claims.issuedAt))
            .expiration(Date.from(claims.expiresAt))
            .signWith(signingKey)
            .compact()

    override fun parse(token: String): JwtClaims {
        val claims = try {
            Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .payload
        } catch (e: ExpiredJwtException) {
            throw InvalidJwtException("만료된 토큰입니다", e)
        } catch (e: JwtException) {
            throw InvalidJwtException("유효하지 않은 토큰입니다", e)
        }

        return JwtClaims(
            userId = UserId.of(claims.subject),
            issuedAt = claims.issuedAt.toInstant(),
            expiresAt = claims.expiration.toInstant(),
        )
    }
}
