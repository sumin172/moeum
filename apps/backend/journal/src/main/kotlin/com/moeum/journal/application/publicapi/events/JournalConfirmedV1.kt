package com.moeum.journal.application.publicapi.events

import com.moeum.kernel.IntegrationEvent
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// 사용자가 일기를 확정했다. 수정 후 재확정하면 같은 일기에 대해 다시 발행된다 — 소비자(예: Stage 4 보상)는
// journalId(+revision)로 중복을 판단한다. 지금은 in-process로만 발행한다(Outbox는 Stage 4).
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
