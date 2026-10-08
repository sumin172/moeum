package com.moeum.journal.infrastructure.jpa

import com.moeum.journal.domain.model.JournalLifecycleStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "journals", schema = "journal")
class JournalJpaEntity(
    @field:Id
    @field:Column(name = "id")
    private val entityId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "diary_date", nullable = false)
    val diaryDate: LocalDate,
    @Column(name = "lifecycle_status", nullable = false)
    @Enumerated(EnumType.STRING)
    val lifecycleStatus: JournalLifecycleStatus,
    @Column(nullable = false)
    val title: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val content: String,
    @Column(name = "current_revision", nullable = false)
    val currentRevision: Int,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
    @Column(name = "confirmed_at")
    val confirmedAt: Instant? = null,
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
    @Column(name = "purge_after")
    val purgeAfter: Instant? = null,
) : Persistable<UUID> {
    override fun getId(): UUID = entityId
    override fun isNew(): Boolean = false
}
