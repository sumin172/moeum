package com.moeum.identity.infrastructure.jpa

import com.moeum.identity.domain.UserRepository
import com.moeum.identity.domain.model.User
import com.moeum.kernel.UserId
import org.springframework.stereotype.Component

@Component
class UserRepositoryImpl(
    private val jpaRepository: UserJpaRepository,
) : UserRepository {

    override fun findByGoogleId(googleId: String): User? =
        jpaRepository.findByGoogleId(googleId)?.toDomain()

    override fun save(user: User): User =
        jpaRepository.save(user.toEntity()).toDomain()
}

private fun UserJpaEntity.toDomain(): User =
    User(
        id = UserId(id),
        googleId = googleId,
        email = email,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )

private fun User.toEntity(): UserJpaEntity =
    UserJpaEntity(
        id = id.value,
        googleId = googleId,
        email = email,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )
