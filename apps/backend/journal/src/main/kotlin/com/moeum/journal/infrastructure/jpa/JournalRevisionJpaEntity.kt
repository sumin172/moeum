package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.model.JournalRevisionEditor
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "journal_revisions", schema = "journal")
class JournalRevisionJpaEntity(
    @Id
    val id: UUID,
    @Column(name = "journal_id", nullable = false)
    val journalId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "revision_no", nullable = false)
    val revisionNo: Int,
    @Column(nullable = false)
    val title: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val content: String,
    @Column(name = "edited_by", nullable = false)
    @Enumerated(EnumType.STRING)
    val editedBy: JournalRevisionEditor,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
