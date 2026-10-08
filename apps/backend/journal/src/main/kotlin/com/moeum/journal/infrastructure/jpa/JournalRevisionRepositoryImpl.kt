package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.JournalRevisionRepository
import com.moeum.journal.domain.model.JournalRevision
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JournalRevisionRepositoryImpl(
    private val entityManager: EntityManager,
) : JournalRevisionRepository {

    // 추가만 하는 이력이므로 merge(SELECT 후 INSERT) 대신 persist로 바로 INSERT한다
    override fun append(revision: JournalRevision): JournalRevision {
        entityManager.persist(revision.toEntity())
        return revision
    }
}

private fun JournalRevision.toEntity(): JournalRevisionJpaEntity =
    JournalRevisionJpaEntity(
        id = id.value,
        journalId = journalId.value,
        userId = userId.value,
        revisionNo = revisionNo,
        title = title,
        content = content,
        editedBy = editedBy,
        createdAt = createdAt,
    )
