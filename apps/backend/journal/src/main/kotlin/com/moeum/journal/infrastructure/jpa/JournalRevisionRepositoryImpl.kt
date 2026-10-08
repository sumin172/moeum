package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.JournalRevisionRepository
import com.moeum.journal.domain.model.JournalRevision
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component

@Component
class JournalRevisionRepositoryImpl(
    private val entityManager: EntityManager,
    private val contentCodec: JournalContentCodec,
) : JournalRevisionRepository {

    // 추가만 하는 이력이므로 merge(SELECT 후 INSERT) 대신 persist로 바로 INSERT한다
    override fun append(revision: JournalRevision): JournalRevision {
        entityManager.persist(
            JournalRevisionJpaEntity(
                id = revision.id.value,
                journalId = revision.journalId.value,
                userId = revision.userId.value,
                revisionNo = revision.revisionNo,
                title = revision.title,
                content = contentCodec.toJson(revision.body),
                editedBy = revision.editedBy,
                createdAt = revision.createdAt,
            ),
        )
        return revision
    }
}
