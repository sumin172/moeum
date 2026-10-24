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

private const val CLAIM_BATCH_SQL =
    "UPDATE $TABLE ${JobClaimSql.CLAIM_SET} " +
        "WHERE id IN (SELECT DISTINCT ON (record_day_id) id FROM $TABLE " +
        "WHERE record_day_id IN (:recordIds) AND status IN ('PENDING','PROCESSING') " +
        "ORDER BY record_day_id,input_source_revision) AND (${JobClaimSql.CLAIMABLE}) RETURNING *"

@Component
class ResponseJobRepositoryImpl(
    private val jpaRepository: ResponseJobJpaRepository,
    private val entityManager: EntityManager,
) : ResponseJobRepository {

    override fun insert(job: ResponseJob) { entityManager.persist(job.toEntity()) }

    override fun save(job: ResponseJob): ResponseJob =
        jpaRepository.save(job.toEntity()).toDomain()

    override fun findByUserIdAndUserMessageId(userId: UserId, userMessageId: MessageId): ResponseJob? =
        jpaRepository.findByUserIdAndUserMessageId(userId.value, userMessageId.value)?.toDomain()

    override fun findAllByUserIdAndUserMessageIdIn(userId: UserId, userMessageIds: Collection<MessageId>): List<ResponseJob> =
        if (userMessageIds.isEmpty()) emptyList()
        else jpaRepository.findAllByUserIdAndUserMessageIdIn(userId.value, userMessageIds.map { it.value }).map { it.toDomain() }

    override fun hasLaterStarted(job: ResponseJob): Boolean =
        (entityManager.createNativeQuery("""
            SELECT EXISTS(SELECT 1 FROM conversation.messages
                WHERE user_id=:userId AND record_day_id=:recordId AND source_sequence>:revision AND role='ASSISTANT')
                OR EXISTS(SELECT 1 FROM conversation.response_jobs
                WHERE record_day_id=:recordId AND input_source_revision>:revision AND status='PROCESSING')
        """).setParameter("userId",job.userId.value).setParameter("recordId",job.recordDayId.value)
            .setParameter("revision",job.inputSourceRevision).singleResult as Boolean)

    override fun supersededIds(userId: UserId, ids: Collection<ResponseJobId>): Set<ResponseJobId> {
        if (ids.isEmpty()) return emptySet()
        return entityManager.createNativeQuery("""
            SELECT j.id FROM conversation.response_jobs j WHERE j.user_id=:userId AND j.id IN (:ids)
            AND (EXISTS (SELECT 1 FROM conversation.messages later_message
                WHERE later_message.user_id=j.user_id AND later_message.record_day_id=j.record_day_id
                AND later_message.source_sequence>j.input_source_revision AND later_message.role='ASSISTANT')
            OR EXISTS (SELECT 1 FROM conversation.response_jobs later
                WHERE later.record_day_id=j.record_day_id AND later.input_source_revision>j.input_source_revision
                AND later.status='PROCESSING'))
        """).setParameter("userId",userId.value).setParameter("ids",ids.map { it.value })
            .resultList.map { ResponseJobId(it as java.util.UUID) }.toSet()
    }

    override fun findAllByUserIdAndIdIn(userId: UserId, ids: Collection<ResponseJobId>): List<ResponseJob> =
        if (ids.isEmpty()) emptyList() else jpaRepository.findAllByUserIdAndIdIn(userId.value,ids.map { it.value }).map { it.toDomain() }

    @Transactional
    override fun claimBatch(now: Instant, leaseExpiresAt: Instant, limit: Int): List<ResponseJob> {
        require(limit in 1..64)
        val recordIds=entityManager.createNativeQuery("""
            SELECT r.id FROM conversation.response_jobs j
            JOIN conversation.record_days r ON r.id=j.record_day_id
            WHERE j.status IN ('PENDING','PROCESSING')
                AND (CASE WHEN j.status='PENDING' THEN j.next_attempt_at ELSE j.lease_expires_at END)<=:now
                AND NOT EXISTS (SELECT 1 FROM conversation.response_jobs earlier
                    WHERE earlier.record_day_id=j.record_day_id
                    AND earlier.input_source_revision<j.input_source_revision
                    AND earlier.status IN ('PENDING','PROCESSING'))
            ORDER BY (CASE WHEN j.status='PENDING' THEN j.next_attempt_at ELSE j.lease_expires_at END),j.created_at
            LIMIT :limit FOR UPDATE OF r SKIP LOCKED
        """).setParameter("now",now).setParameter("limit",limit).resultList
        if(recordIds.isEmpty())return emptyList()
        // 기록 잠금 획득 후 새로운 READ COMMITTED 스냅샷에서 실제 선두를 다시 검사한다.
        // 후보 조회 중 과거 작업이 재시작됐더라도 후속 작업을 먼저 실행하지 않는다.
        return entityManager.createNativeQuery(CLAIM_BATCH_SQL, ResponseJobJpaEntity::class.java)
            .setParameter("recordIds",recordIds).setParameter("now",now).setParameter("leaseExpiresAt",leaseExpiresAt)
            .resultList.map { (it as ResponseJobJpaEntity).toDomain() }
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
            version = version,
        ),
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
        attemptCount = state.attemptCount,
        nextAttemptAt = state.nextAttemptAt,
        leaseExpiresAt = state.leaseExpiresAt,
        version = state.version,
        createdAt = createdAt,
        updatedAt = updatedAt,
        inputSourceRevision = inputSourceRevision,
    )
