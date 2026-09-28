package com.moeum.conversation.domain.model

import java.time.Instant

data class Moment(
    val id: MomentId,
    val momentSetId: MomentSetId,
    val conversationDayId: ConversationDayId,
    val type: String,
    val summary: String,
    val emotion: String? = null,
    val confidence: Float? = null,
    val occurredAt: Instant? = null,
    val deletedAt: Instant? = null,
)
