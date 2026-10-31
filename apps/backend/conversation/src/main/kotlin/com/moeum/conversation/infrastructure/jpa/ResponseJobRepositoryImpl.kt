package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.ResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobClaimSql
import com.moeum.platform.job.JobState
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private const val TABLE = "conversation.response_jobs"

// 새 실행 주기(attempt_count=0)만 잠근 기록의 현재 원본 순번을 관측 상한으로 잡는다. 재시도·리스 회수는 기존 상한을 유지한다.
private const val CLAIM_BATCH_SQL =
    "UPDATE $TABLE j ${JobClaimSql.CLAIM_SET}, " +
        "observed_source_revision = CASE WHEN j.attempt_count = 0 THEN r.source_revision ELSE j.observed_source_revision END " +
        "FROM conversation.record_days r " +
        "WHERE r.id = j.record_day_id AND j.record_day_id IN (:recordIds) AND (${JobClaimSql.CLAIMABLE}) " +
        "RETURNING j.*"

@Component
class ResponseJobRepositoryImpl(
    private val jpaRepository: ResponseJobJpaRepository,
    private val entityManager: EntityManager,
) : ResponseJobRepository {

    override fun insert(job: ResponseJob) { entityManager.persist(job.toEntity()) }

    override fun save(job: ResponseJob): ResponseJob =
        jpaRepository.save(job.toEntity()).toDomain()

    override fun findByRecord(userId: UserId, recordDayId: RecordDayId): ResponseJob? =
        jpaRepository.findByUserIdAndRecordDayId(userId.value, recordDayId.value)?.toDomain()

    @Transactional
    override fun claimBatch(now: Instant, leaseExpiresAt: Instant, limit: Int): List<ResponseJob> {
        require(limit in 1..64)
        val recordIds=entityManager.createNativeQuery("""
            SELECT r.id FROM conversation.response_jobs j
            JOIN conversation.record_days r ON r.id=j.record_day_id
            WHERE j.status IN ('PENDING','PROCESSING')
                AND (CASE WHEN j.status='PENDING' THEN j.next_attempt_at ELSE j.lease_expires_at END)<=:now
            ORDER BY (CASE WHEN j.status='PENDING' THEN j.next_attempt_at ELSE j.lease_expires_at END),j.created_at
            LIMIT :limit FOR UPDATE OF r SKIP LOCKED
        """).setParameter("now",now).setParameter("limit",limit).resultList
        if(recordIds.isEmpty())return emptyList()
        // 기록 잠금 획득 후 새로운 READ COMMITTED 문장에서 작업 상태와 원본 순번을 다시 읽는다.
        // 후보 조회 중 커밋된 접수·재개가 있어도 잠금 뒤의 최신 값으로 선점 여부와 관측 상한을 정한다.
        return entityManager.createNativeQuery(CLAIM_BATCH_SQL, ResponseJobJpaEntity::class.java)
            .setParameter("recordIds",recordIds).setParameter("now",now).setParameter("leaseExpiresAt",leaseExpiresAt)
            .resultList.map { (it as ResponseJobJpaEntity).toDomain() }
    }
}

private fun ResponseJobJpaEntity.toDomain(): ResponseJob =
    ResponseJob(
        recordDayId = RecordDayId(recordDayId),
        userId = UserId(userId),
        state = JobState(
            status = status,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
            leaseExpiresAt = leaseExpiresAt,
            version = version,
        ),
        observedSourceRevision = observedSourceRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun ResponseJob.toEntity(): ResponseJobJpaEntity =
    ResponseJobJpaEntity(
        recordDayId = recordDayId.value,
        userId = userId.value,
        observedSourceRevision = observedSourceRevision,
        status = state.status,
        attemptCount = state.attemptCount,
        nextAttemptAt = state.nextAttemptAt,
        leaseExpiresAt = state.leaseExpiresAt,
        version = state.version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
