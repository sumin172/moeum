package com.moeum.identity.domain

import com.moeum.identity.domain.model.User

interface UserRepository {
    fun findByGoogleId(googleId: String): User?
    fun save(user: User): User
}
