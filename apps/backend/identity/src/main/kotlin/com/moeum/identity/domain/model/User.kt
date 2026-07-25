package com.moeum.identity.domain.model

import com.moeum.kernel.UserId
import java.time.Instant

data class User(
    val id: UserId,
    val googleId: String,
    val email: String,
    val createdAt: Instant,
    val deletedAt: Instant? = null,
) {
    companion object {
        fun create(id: UserId, googleId: String, email: String, now: Instant): User =
            User(id = id, googleId = googleId, email = email, createdAt = now)
    }
}