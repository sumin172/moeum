package com.moeum.journal.application.publicapi.events

import com.moeum.kernel.IntegrationEvent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 사용자가 일기를 확정했다. 현재 업무 소비자는 없으며 in-process로만 발행한다.
// 재설계 R4에서 수동 확정 흐름과 함께 제거한다(docs/DEVELOPMENT_STAGES.md).
data class JournalConfirmedV1(
    override val eventId: UUID,
    override val occurredAt: Instant,
    override val correlationId: UUID,
    override val causationId: UUID? = null,
    val journalId: UUID,
    val userId: UUID,
    val diaryDate: LocalDate,
    // 확정된 내용의 리비전 번호
    val revision: Int,
) : IntegrationEvent {
    override val eventType: String = "JournalConfirmed"
    override val eventVersion: Int = 1
}
