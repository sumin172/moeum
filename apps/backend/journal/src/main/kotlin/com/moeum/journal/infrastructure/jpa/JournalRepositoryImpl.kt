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
    private val contentCodec: JournalContentCodec,
) : JournalRepository {

    override fun findByUserIdAndDiaryDate(userId: UserId, diaryDate: LocalDate): Journal? =
        jpaRepository.findByUserIdAndDiaryDate(userId.value, diaryDate)?.toDomain()

    // 바로 flush한다 — @Version은 flush 때 올라가므로, 그래야 돌려주는 일기의 version이 DB와 같다.
    // 클라이언트는 응답의 version을 다음 수정·확정 요청에 그대로 보낸다.
    override fun save(journal: Journal): Journal =
        jpaRepository.saveAndFlush(journal.toEntity()).toDomain()

    private fun JournalJpaEntity.toDomain(): Journal =
        Journal(
            id = JournalId(id),
            userId = UserId(userId),
            diaryDate = diaryDate,
            lifecycleStatus = lifecycleStatus,
            title = title,
            body = contentCodec.bodyOf(content),
            currentRevision = currentRevision,
            version = version,
            sourceLastMessageId = sourceLastMessageId,
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
            content = contentCodec.toJson(body),
            currentRevision = currentRevision,
            version = version,
            sourceLastMessageId = sourceLastMessageId,
            confirmedAt = confirmedAt,
            deletedAt = deletedAt,
            purgeAfter = purgeAfter,
        )
}
