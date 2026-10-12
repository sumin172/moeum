package com.moeum.identity.infrastructure.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users", schema = "identity")
class UserJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "google_id", nullable = false, unique = true)
    val googleId: String,
    @Column(nullable = false)
    val email: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
)
