package com.moeum.conversation.application.command

import com.moeum.conversation.application.publicapi.events.ConversationDayClosedV1
import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.model.ConversationDay
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class CloseSingleConversationDayService(
    private val conversationDayRepository: ConversationDayRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(CloseSingleConversationDayService::class.java)

    // day가 여전히 OPEN일 때만 원자적으로 CLOSED 전환 후 ConversationDayClosed를 발행한다.
    @Transactional
    fun closeIfOpen(day: ConversationDay, now: Instant): Boolean {
        val closed = conversationDayRepository.closeIfOpen(day.id, now)
        if (!closed) {
            log.info("이미 다른 처리로 마감된 ConversationDay, 스킵: id={}", day.id.value)
            return false
        }

        eventPublisher.publishEvent(
            ConversationDayClosedV1(
                occurredAt = now,
                conversationDayId = day.id.value,
                userId = day.userId.value,
                localDate = day.localDate,
            ),
        )
        log.info("ConversationDay 마감: id={}, userId={}, localDate={}", day.id.value, day.userId.value, day.localDate)
        return true
    }
}
