package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalId
import com.moeum.kernel.UserId
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class JournalRepositoryImpl(
    private val jpaRepository: JournalJpaRepository,
) : JournalRepository {

    override fun findById(id: JournalId): Journal? =
        jpaRepository.findById(id.value).map { it.toDomain() }.orElse(null)

    override fun findByUserIdAndDiaryDate(userId: UserId, diaryDate: LocalDate): Journal? =
        jpaRepository.findByUserIdAndDiaryDate(userId.value, diaryDate)?.toDomain()

    override fun save(journal: Journal): Journal =
        jpaRepository.save(journal.toEntity()).toDomain()
}

private fun JournalJpaEntity.toDomain(): Journal =
    Journal(
        id = JournalId(id),
        userId = UserId(userId),
        diaryDate = diaryDate,
        lifecycleStatus = lifecycleStatus,
        title = title,
        content = content,
        currentRevision = currentRevision,
        version = version,
        confirmedAt = confirmedAt,
        deletedAt = deletedAt,
        purgeAfter = purgeAfter,
    )

private fun Journal.toEntity(): JournalJpaEntity =
    JournalJpaEntity(
        entityId = id.value,
        userId = userId.value,
        diaryDate = diaryDate,
        lifecycleStatus = lifecycleStatus,
        title = title,
        content = content,
        currentRevision = currentRevision,
        version = version,
        confirmedAt = confirmedAt,
        deletedAt = deletedAt,
        purgeAfter = purgeAfter,
    )
