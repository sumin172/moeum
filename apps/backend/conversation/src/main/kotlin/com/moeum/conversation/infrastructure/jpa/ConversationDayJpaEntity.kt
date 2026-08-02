package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.model.ConversationDayStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "conversation_days", schema = "conversation")
class ConversationDayJpaEntity(
    @field:Id
    @field:Column(name = "id")
    private val entityId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "local_date", nullable = false)
    val localDate: LocalDate,
    @Column(nullable = false)
    val timezone: String,
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val status: ConversationDayStatus,
    @Column(name = "source_revision", nullable = false)
    val sourceRevision: Long,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
    @Column(name = "opened_at", nullable = false)
    val openedAt: Instant,
    @Column(name = "closed_at")
    val closedAt: Instant? = null,
) : Persistable<UUID> {
    override fun getId(): UUID = entityId
    override fun isNew(): Boolean = false
}
