package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.JournalRevisionRepository
import com.moeum.journal.domain.model.JournalId
import com.moeum.journal.domain.model.JournalRevision
import com.moeum.journal.domain.model.JournalRevisionId
import org.springframework.stereotype.Component

@Component
class JournalRevisionRepositoryImpl(
    private val jpaRepository: JournalRevisionJpaRepository,
) : JournalRevisionRepository {

    override fun save(revision: JournalRevision): JournalRevision =
        jpaRepository.save(revision.toEntity()).toDomain()
}

private fun JournalRevisionJpaEntity.toDomain(): JournalRevision =
    JournalRevision(
        id = JournalRevisionId(id),
        journalId = JournalId(journalId),
        revisionNo = revisionNo,
        title = title,
        content = content,
        editedBy = editedBy,
        createdAt = createdAt,
    )

private fun JournalRevision.toEntity(): JournalRevisionJpaEntity =
    JournalRevisionJpaEntity(
        id = id.value,
        journalId = journalId.value,
        revisionNo = revisionNo,
        title = title,
        content = content,
        editedBy = editedBy,
        createdAt = createdAt,
    )
