package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.MomentExtractionJobRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.MomentExtractionJob
import com.moeum.conversation.domain.model.MomentExtractionJobId
import org.springframework.stereotype.Component

@Component
class MomentExtractionJobRepositoryImpl(
    private val jpaRepository: MomentExtractionJobJpaRepository,
) : MomentExtractionJobRepository {

    override fun save(job: MomentExtractionJob): MomentExtractionJob =
        jpaRepository.save(job.toEntity()).toDomain()
}

private fun MomentExtractionJobJpaEntity.toDomain(): MomentExtractionJob =
    MomentExtractionJob(
        id = MomentExtractionJobId(id),
        conversationDayId = ConversationDayId(conversationDayId),
        sourceRevision = sourceRevision,
        requestKey = requestKey,
        status = status,
        attemptCount = attemptCount,
        errorCode = errorCode,
        createdAt = createdAt,
    )

private fun MomentExtractionJob.toEntity(): MomentExtractionJobJpaEntity =
    MomentExtractionJobJpaEntity(
        id = id.value,
        conversationDayId = conversationDayId.value,
        sourceRevision = sourceRevision,
        requestKey = requestKey,
        status = status,
        attemptCount = attemptCount,
        errorCode = errorCode,
        createdAt = createdAt,
    )
