package com.moeum.conversation.infrastructure.scheduler

import com.moeum.conversation.application.command.CloseConversationDaysService
import com.moeum.conversation.infrastructure.config.ConversationDayCloseProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ConversationDayCloseScheduler(
    private val closeConversationDaysService: CloseConversationDaysService,
    private val properties: ConversationDayCloseProperties,
) {
    private val log = LoggerFactory.getLogger(ConversationDayCloseScheduler::class.java)

    // 이전 스캔이 끝난 뒤부터 간격을 재는 fixedDelay — 스캔이 밀려도 동시에 두 스캔이 겹치지 않는다.
    @Scheduled(fixedDelayString = $$"${moeum.conversation.day-close.scan-interval:PT5M}")
    fun scan() {
        runCatching { closeConversationDaysService.closeDueDays(properties.batchSize) }
            .onFailure { e -> log.error("ConversationDay 마감 배치 스캔 실패", e) }
    }
}
