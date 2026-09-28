package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.MomentSetRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.MomentSet
import com.moeum.conversation.domain.model.MomentSetId
import org.springframework.stereotype.Component

@Component
class MomentSetRepositoryImpl(
    private val jpaRepository: MomentSetJpaRepository,
) : MomentSetRepository {

    override fun save(momentSet: MomentSet): MomentSet =
        jpaRepository.save(momentSet.toEntity()).toDomain()

    override fun findByConversationDayIdAndSourceRevision(conversationDayId: ConversationDayId, sourceRevision: Long): MomentSet? =
        jpaRepository.findByConversationDayIdAndSourceRevision(conversationDayId.value, sourceRevision)?.toDomain()
}

private fun MomentSetJpaEntity.toDomain(): MomentSet =
    MomentSet(
        id = MomentSetId(id),
        conversationDayId = ConversationDayId(conversationDayId),
        sourceRevision = sourceRevision,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        isCurrent = isCurrent,
        supersededAt = supersededAt,
        createdAt = createdAt,
    )

private fun MomentSet.toEntity(): MomentSetJpaEntity =
    MomentSetJpaEntity(
        id = id.value,
        conversationDayId = conversationDayId.value,
        sourceRevision = sourceRevision,
        generationId = generationId,
        model = model,
        promptVersion = promptVersion,
        isCurrent = isCurrent,
        supersededAt = supersededAt,
        createdAt = createdAt,
    )
