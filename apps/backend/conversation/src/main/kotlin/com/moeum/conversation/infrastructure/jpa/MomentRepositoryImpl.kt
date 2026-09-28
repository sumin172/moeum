package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.MomentRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Moment
import com.moeum.conversation.domain.model.MomentId
import com.moeum.conversation.domain.model.MomentSetId
import org.springframework.stereotype.Component

@Component
class MomentRepositoryImpl(
    private val jpaRepository: MomentJpaRepository,
) : MomentRepository {

    override fun saveAll(moments: List<Moment>): List<Moment> =
        jpaRepository.saveAll(moments.map { it.toEntity() }).map { it.toDomain() }

    override fun findAllByMomentSetId(momentSetId: MomentSetId): List<Moment> =
        jpaRepository.findAllByMomentSetId(momentSetId.value).map { it.toDomain() }
}

private fun MomentJpaEntity.toDomain(): Moment =
    Moment(
        id = MomentId(id),
        momentSetId = MomentSetId(momentSetId),
        conversationDayId = ConversationDayId(conversationDayId),
        type = type,
        summary = summary,
        emotion = emotion,
        confidence = confidence,
        occurredAt = occurredAt,
        deletedAt = deletedAt,
    )

private fun Moment.toEntity(): MomentJpaEntity =
    MomentJpaEntity(
        id = id.value,
        momentSetId = momentSetId.value,
        conversationDayId = conversationDayId.value,
        type = type,
        summary = summary,
        emotion = emotion,
        confidence = confidence,
        occurredAt = occurredAt,
        deletedAt = deletedAt,
    )
