package com.moeum.platform.security

import com.moeum.kernel.UserId
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
            .claim("subscriptionTier", claims.subscriptionTier)
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
            subscriptionTier = claims["subscriptionTier"] as? String,
            issuedAt = claims.issuedAt.toInstant(),
            expiresAt = claims.expiration.toInstant(),
        )
    }
}
