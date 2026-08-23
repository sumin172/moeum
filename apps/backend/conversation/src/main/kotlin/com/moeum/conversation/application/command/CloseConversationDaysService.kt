package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.kernel.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CloseConversationDaysService(
    private val conversationDayRepository: ConversationDayRepository,
    private val closeSingleConversationDayService: CloseSingleConversationDayService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(CloseConversationDaysService::class.java)

    fun closeDueDays(batchSize: Int): Int {
        val now = timeProvider.now()
        val candidates = conversationDayRepository.findOpenDueForClose(now, batchSize)
        if (candidates.isEmpty()) {
            return 0
        }
        log.info("마감 대상 ConversationDay {}건 발견", candidates.size)
        return candidates.count { closeSingleConversationDayService.closeIfOpen(it, now) }
    }
}
