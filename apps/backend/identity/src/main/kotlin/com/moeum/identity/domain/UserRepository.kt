package com.moeum.identity.domain

import com.moeum.identity.domain.model.User
import com.moeum.kernel.UserId

interface UserRepository {
    fun findById(id: UserId): User?
    fun findByGoogleId(googleId: String): User?
    fun save(user: User): User
}
