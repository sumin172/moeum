package com.moeum.platform.security

import com.moeum.kernel.UserId
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

interface CurrentUserProvider {
    fun currentUserId(): UserId
}

@Component
class SecurityContextCurrentUserProvider : CurrentUserProvider {

    override fun currentUserId(): UserId {
        val principal = SecurityContextHolder.getContext().authentication?.principal
            ?: throw IllegalStateException("인증된 사용자가 없습니다")

        return principal as? UserId
            ?: throw IllegalStateException("인증 principal이 UserId 타입이 아닙니다: $principal")
    }
}
