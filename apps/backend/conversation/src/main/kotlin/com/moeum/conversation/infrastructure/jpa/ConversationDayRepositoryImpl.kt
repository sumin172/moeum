package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.ConversationDayStatus
import com.moeum.kernel.UserId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate

@Component
class ConversationDayRepositoryImpl(
    private val jpaRepository: ConversationDayJpaRepository,
) : ConversationDayRepository {

    override fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay? =
        jpaRepository.findByUserIdAndLocalDate(userId.value, localDate)?.toDomain()

    override fun save(conversationDay: ConversationDay): ConversationDay =
        jpaRepository.save(conversationDay.toEntity()).toDomain()

    override fun findOpenDueForClose(now: Instant, limit: Int): List<ConversationDay> =
        jpaRepository.findByStatusAndClosesAtLessThanEqual(ConversationDayStatus.OPEN, now, PageRequest.of(0, limit))
            .map { it.toDomain() }

    override fun closeIfOpen(id: ConversationDayId, closedAt: Instant): Boolean =
        jpaRepository.closeIfOpen(id.value, closedAt, ConversationDayStatus.OPEN, ConversationDayStatus.CLOSED) > 0
}

private fun ConversationDayJpaEntity.toDomain(): ConversationDay =
    ConversationDay(
        id = ConversationDayId(id),
        userId = UserId(userId),
        localDate = localDate,
        timezone = timezone,
        status = status,
        sourceRevision = sourceRevision,
        version = version,
        openedAt = openedAt,
        closesAt = closesAt,
        closedAt = closedAt,
    )

private fun ConversationDay.toEntity(): ConversationDayJpaEntity =
    ConversationDayJpaEntity(
        entityId = id.value,
        userId = userId.value,
        localDate = localDate,
        timezone = timezone,
        status = status,
        sourceRevision = sourceRevision,
        version = version,
        openedAt = openedAt,
        closesAt = closesAt,
        closedAt = closedAt,
    )
