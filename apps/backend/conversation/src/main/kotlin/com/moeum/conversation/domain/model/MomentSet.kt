package com.moeum.conversation.domain.model

import java.time.Instant
import java.util.UUID

data class MomentSet(
    val id: MomentSetId,
    val conversationDayId: ConversationDayId,
    // 추출 시점에 읽은 ConversationDay의 revision.
    val sourceRevision: Long,
    val generationId: UUID,
    val model: String,
    val promptVersion: String,
    val isCurrent: Boolean = true,
    val supersededAt: Instant? = null,
    val createdAt: Instant,
)
