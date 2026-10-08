package com.moeum.identity.infrastructure.jpa

import com.moeum.identity.domain.SubscriptionRepository
import com.moeum.identity.domain.model.Plan
import com.moeum.identity.domain.model.Subscription
import com.moeum.identity.domain.model.SubscriptionId
import com.moeum.identity.domain.model.SubscriptionSource
import com.moeum.kernel.UserId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "subscriptions", schema = "identity")
class SubscriptionJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val plan: Plan,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val source: SubscriptionSource,
    @Column(name = "starts_at", nullable = false)
    val startsAt: Instant,
    @Column(name = "ends_at")
    val endsAt: Instant?,
    @Column(name = "revoked_at")
    val revokedAt: Instant?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

interface SubscriptionJpaRepository : JpaRepository<SubscriptionJpaEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<SubscriptionJpaEntity>
}

@Component
class SubscriptionRepositoryImpl(
    private val jpaRepository: SubscriptionJpaRepository,
) : SubscriptionRepository {

    override fun findAllByUserId(userId: UserId): List<Subscription> =
        jpaRepository.findAllByUserId(userId.value).map { it.toDomain() }

    override fun save(subscription: Subscription): Subscription =
        jpaRepository.save(subscription.toEntity()).toDomain()
}

private fun SubscriptionJpaEntity.toDomain(): Subscription =
    Subscription(SubscriptionId(id), UserId(userId), plan, source, startsAt, endsAt, revokedAt, createdAt)

private fun Subscription.toEntity(): SubscriptionJpaEntity =
    SubscriptionJpaEntity(id.value, userId.value, plan, source, startsAt, endsAt, revokedAt, createdAt)
