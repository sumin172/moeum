package com.moeum.conversation.application.command

import com.moeum.conversation.application.publicapi.events.MomentSnapshotV1
import com.moeum.conversation.application.publicapi.events.MomentsPreparedV1
import com.moeum.conversation.domain.MomentExtractionJobRepository
import com.moeum.conversation.domain.MomentRepository
import com.moeum.conversation.domain.MomentSetRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Moment
import com.moeum.conversation.domain.model.MomentExtractionJob
import com.moeum.conversation.domain.model.MomentId
import com.moeum.conversation.domain.model.MomentSet
import com.moeum.conversation.domain.model.MomentSetId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.platform.llm.moment.ExtractedMoment
import com.moeum.platform.llm.moment.MomentExtractionResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class SaveMomentExtractionResultService(
    private val momentSetRepository: MomentSetRepository,
    private val momentRepository: MomentRepository,
    private val momentExtractionJobRepository: MomentExtractionJobRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun save(
        conversationDayId: ConversationDayId,
        userId: UserId,
        localDate: LocalDate,
        sourceRevision: Long,
        job: MomentExtractionJob,
        extraction: MomentExtractionResponse,
        correlationId: UUID,
    ): List<Moment> {
        val now = timeProvider.now()

        val momentSet = momentSetRepository.save(
            MomentSet(
                id = MomentSetId.generate(),
                conversationDayId = conversationDayId,
                sourceRevision = sourceRevision,
                generationId = extraction.generationId,
                model = extraction.model,
                promptVersion = extraction.promptVersion,
                createdAt = now,
            ),
        )

        val moments = momentRepository.saveAll(
            extraction.moments.map { it.toMoment(momentSet.id, conversationDayId) },
        )

        momentExtractionJobRepository.save(job.completed())

        eventPublisher.publishEvent(
            MomentsPreparedV1(
                occurredAt = now,
                correlationId = correlationId,
                conversationDayId = conversationDayId.value,
                userId = userId.value,
                localDate = localDate,
                sourceRevision = sourceRevision,
                momentSetId = momentSet.id.value,
                moments = moments.map { it.toSnapshot() },
            ),
        )

        return moments
    }
}

private fun ExtractedMoment.toMoment(momentSetId: MomentSetId, conversationDayId: ConversationDayId): Moment =
    Moment(
        id = MomentId.generate(),
        momentSetId = momentSetId,
        conversationDayId = conversationDayId,
        type = type,
        summary = summary,
        emotion = emotion,
        confidence = confidence,
        occurredAt = occurredAt,
    )

private fun Moment.toSnapshot(): MomentSnapshotV1 =
    MomentSnapshotV1(type = type, summary = summary, emotion = emotion, confidence = confidence, occurredAt = occurredAt)
