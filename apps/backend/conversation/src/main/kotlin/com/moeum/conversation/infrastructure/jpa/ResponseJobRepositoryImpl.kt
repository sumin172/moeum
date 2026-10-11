package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.MessageId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.domain.model.ResponseJobId
import com.moeum.kernel.RecordDayId
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
        "WHERE id = (SELECT id FROM $TABLE WHERE record_day_id=:recordId " +
        "AND status IN ('PENDING','PROCESSING') ORDER BY input_source_revision LIMIT 1) " +
        "AND (${JobClaimSql.CLAIMABLE}) RETURNING *"

@Component
class ResponseJobRepositoryImpl(
    private val jpaRepository: ResponseJobJpaRepository,
    private val entityManager: EntityManager,
) : ResponseJobRepository {

    override fun save(job: ResponseJob): ResponseJob =
        jpaRepository.save(job.toEntity()).toDomain()

    override fun findByUserIdAndUserMessageId(userId: UserId, userMessageId: MessageId): ResponseJob? =
        jpaRepository.findByUserIdAndUserMessageId(userId.value, userMessageId.value)?.toDomain()

    override fun findAllByUserIdAndUserMessageIdIn(userId: UserId, userMessageIds: Collection<MessageId>): List<ResponseJob> =
        if (userMessageIds.isEmpty()) emptyList()
        else jpaRepository.findAllByUserIdAndUserMessageIdIn(userId.value, userMessageIds.map { it.value }).map { it.toDomain() }

    override fun hasLaterStarted(job: ResponseJob): Boolean =
        (entityManager.createNativeQuery("""
            SELECT EXISTS(SELECT 1 FROM conversation.response_jobs
                WHERE record_day_id=:recordId AND input_source_revision>:revision AND status IN ('PROCESSING','COMPLETED'))
        """).setParameter("recordId",job.recordDayId.value).setParameter("revision",job.inputSourceRevision).singleResult as Boolean)

    override fun supersededIds(userId: UserId, ids: Collection<ResponseJobId>): Set<ResponseJobId> {
        if (ids.isEmpty()) return emptySet()
        return entityManager.createNativeQuery("""
            SELECT j.id FROM conversation.response_jobs j WHERE j.user_id=:userId AND j.id IN (:ids)
            AND EXISTS (SELECT 1 FROM conversation.response_jobs later
                WHERE later.record_day_id=j.record_day_id AND later.input_source_revision>j.input_source_revision
                AND later.status IN ('PROCESSING','COMPLETED'))
        """).setParameter("userId",userId.value).setParameter("ids",ids.map { it.value })
            .resultList.map { ResponseJobId(it as java.util.UUID) }.toSet()
    }

    override fun findAllByUserIdAndIdIn(userId: UserId, ids: Collection<ResponseJobId>): List<ResponseJob> =
        if (ids.isEmpty()) emptyList() else jpaRepository.findAllByUserIdAndIdIn(userId.value,ids.map { it.value }).map { it.toDomain() }

    @Transactional
    override fun claimNext(now: Instant, leaseExpiresAt: Instant): ResponseJob? {
        // 기록 잠금이 선점·사용자 재시도·결과 저장을 직렬화한다. LLM 실행 동안은 유지하지 않는다.
        val recordId = entityManager.createNativeQuery("""
            SELECT r.id FROM conversation.record_days r
            WHERE r.id IN (
                SELECT j.record_day_id FROM conversation.response_jobs j
                WHERE ((j.status='PENDING' AND j.next_attempt_at<=:now)
                    OR (j.status='PROCESSING' AND j.lease_expires_at<=:now))
                AND NOT EXISTS (SELECT 1 FROM conversation.response_jobs earlier
                    WHERE earlier.record_day_id=j.record_day_id
                    AND earlier.input_source_revision<j.input_source_revision
                    AND earlier.status IN ('PENDING','PROCESSING'))
            )
            ORDER BY r.start_at LIMIT 1 FOR UPDATE SKIP LOCKED
        """).setParameter("now",now).resultList.firstOrNull() ?: return null
        return entityManager.createNativeQuery(CLAIM_NEXT_SQL, ResponseJobJpaEntity::class.java)
            .setParameter("recordId",recordId).setParameter("now",now).setParameter("leaseExpiresAt",leaseExpiresAt)
            .resultList.firstOrNull()?.let { (it as ResponseJobJpaEntity).toDomain() }
    }
}

private fun ResponseJobJpaEntity.toDomain(): ResponseJob =
    ResponseJob(
        id = ResponseJobId(id),
        userMessageId = MessageId(userMessageId),
        userId = UserId(userId),
        recordDayId = RecordDayId(recordDayId),
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
        inputSourceRevision = inputSourceRevision,
    )

private fun ResponseJob.toEntity(): ResponseJobJpaEntity =
    ResponseJobJpaEntity(
        id = id.value,
        userMessageId = userMessageId.value,
        userId = userId.value,
        recordDayId = recordDayId.value,
        status = state.status,
        failureReason = failureReason,
        attemptCount = state.attemptCount,
        nextAttemptAt = state.nextAttemptAt,
        leaseExpiresAt = state.leaseExpiresAt,
        lastErrorCode = state.lastErrorCode,
        version = state.version,
        createdAt = createdAt,
        updatedAt = updatedAt,
        inputSourceRevision = inputSourceRevision,
    )
