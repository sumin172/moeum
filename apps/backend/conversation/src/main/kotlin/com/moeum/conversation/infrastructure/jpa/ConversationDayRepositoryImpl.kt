package com.moeum.conversation.infrastructure.jpa

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.model.ConversationDay
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.kernel.UserId
import org.springframework.stereotype.Component
import java.time.LocalDate

@Component
class ConversationDayRepositoryImpl(
    private val jpaRepository: ConversationDayJpaRepository,
) : ConversationDayRepository {

    override fun findByUserIdAndLocalDate(userId: UserId, localDate: LocalDate): ConversationDay? =
        jpaRepository.findByUserIdAndLocalDate(userId.value, localDate)?.toDomain()

    override fun save(conversationDay: ConversationDay): ConversationDay =
        jpaRepository.save(conversationDay.toEntity()).toDomain()
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
        closedAt = closedAt,
    )
