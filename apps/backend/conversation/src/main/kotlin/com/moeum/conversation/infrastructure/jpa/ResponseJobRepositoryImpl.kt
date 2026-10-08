package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.model.ResponseJobId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobClaimSql
import com.moeum.platform.job.JobState
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private const val TABLE = "conversation.response_jobs"

private const val CLAIM_NEXT_SQL =
    "UPDATE $TABLE ${JobClaimSql.CLAIM_SET} " +
        "WHERE id = (SELECT id FROM $TABLE WHERE ${JobClaimSql.CLAIMABLE} ${JobClaimSql.PICK_ONE}) RETURNING *"

private const val CLAIM_BY_ID_SQL =
    "UPDATE $TABLE ${JobClaimSql.CLAIM_SET} WHERE ${JobClaimSql.CLAIMABLE_BY_ID} RETURNING *"

@Component
class ResponseJobRepositoryImpl(
    private val jpaRepository: ResponseJobJpaRepository,
    private val entityManager: EntityManager,
) : ResponseJobRepository {

    override fun save(job: ResponseJob): ResponseJob =
        jpaRepository.save(job.toEntity()).toDomain()

    override fun findByUserMessageId(userMessageId: MessageId): ResponseJob? =
        jpaRepository.findByUserMessageId(userMessageId.value)?.toDomain()

    override fun findAllByUserMessageIdIn(userMessageIds: Collection<MessageId>): List<ResponseJob> =
        if (userMessageIds.isEmpty()) emptyList()
        else jpaRepository.findAllByUserMessageIdIn(userMessageIds.map { it.value }).map { it.toDomain() }

    @Transactional
    override fun claimNext(now: Instant, leaseExpiresAt: Instant): ResponseJob? =
        entityManager.createNativeQuery(CLAIM_NEXT_SQL, ResponseJobJpaEntity::class.java)
            .setParameter("now", now)
            .setParameter("leaseExpiresAt", leaseExpiresAt)
            .resultList
            .firstOrNull()
            ?.let { (it as ResponseJobJpaEntity).toDomain() }

    @Transactional
    override fun claim(id: ResponseJobId, now: Instant, leaseExpiresAt: Instant): ResponseJob? =
        entityManager.createNativeQuery(CLAIM_BY_ID_SQL, ResponseJobJpaEntity::class.java)
            .setParameter("id", id.value)
            .setParameter("now", now)
            .setParameter("leaseExpiresAt", leaseExpiresAt)
            .resultList
            .firstOrNull()
            ?.let { (it as ResponseJobJpaEntity).toDomain() }
}

private fun ResponseJobJpaEntity.toDomain(): ResponseJob =
    ResponseJob(
        id = ResponseJobId(id),
        userMessageId = MessageId(userMessageId),
        userId = UserId(userId),
        dayDate = dayDate,
        state = JobState(
            status = status,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            leaseExpiresAt = leaseExpiresAt,
            lastErrorCode = lastErrorCode,
            version = version,
        ),
        failureReason = failureReason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun ResponseJob.toEntity(): ResponseJobJpaEntity =
    ResponseJobJpaEntity(
        id = id.value,
        userMessageId = userMessageId.value,
        userId = userId.value,
        dayDate = dayDate,
        status = state.status,
        failureReason = failureReason,
        attemptCount = state.attemptCount,
        nextAttemptAt = state.nextAttemptAt,
        leaseExpiresAt = state.leaseExpiresAt,
        lastErrorCode = state.lastErrorCode,
        version = state.version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
