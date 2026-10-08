package com.moeum.integration.support

import com.moeum.kernel.IntegrationEvent
import org.springframework.context.event.EventListener
import java.util.concurrent.CopyOnWriteArrayList

// 요청 처리 스레드에서 발행된 Integration Event까지 모은다(@RecordApplicationEvents는 테스트 스레드만 기록한다).
class IntegrationEventRecorder {
    val events = CopyOnWriteArrayList<IntegrationEvent>()

    @EventListener
    fun on(event: IntegrationEvent) {
        events += event
    }
}
