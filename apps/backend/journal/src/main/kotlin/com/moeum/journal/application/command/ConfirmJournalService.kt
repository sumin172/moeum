package com.moeum.journal.application.command

import com.moeum.journal.application.publicapi.events.JournalConfirmedV1
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.JournalVersionConflictException
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import com.moeum.kernel.UuidV7
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
class ConfirmJournalService(
    private val journalRepository: JournalRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val timeProvider: TimeProvider,
) {
    // 확정은 요금제와 무관하게 모두 할 수 있다. 이미 확정된 일기를 다시 확정하면 아무것도 바꾸지 않고 이벤트도 내지 않는다.
    // correlationId: 이 확정을 시작한 요청의 식별자 — 이벤트로 이어지는 흐름을 추적한다.
    @Transactional
    fun confirm(userId: UserId, diaryDate: LocalDate, expectedVersion: Long, correlationId: UUID): Journal {
        val journal = journalRepository.findByUserIdAndDiaryDate(userId, diaryDate)
            ?: throw JournalNotFoundException("일기가 없습니다: diaryDate=$diaryDate")
        if (journal.version != expectedVersion) {
            throw JournalVersionConflictException("다른 기기에서 먼저 바뀐 일기입니다: expected=$expectedVersion, actual=${journal.version}")
        }
        if (journal.isConfirmed) return journal

        val now = timeProvider.now()
        val confirmed = journalRepository.save(journal.confirmed(now))
        eventPublisher.publishEvent(
            JournalConfirmedV1(
                eventId = UuidV7.generate(),
                occurredAt = now,
                correlationId = correlationId,
                journalId = confirmed.id.value,
                userId = userId.value,
                diaryDate = diaryDate,
                revision = confirmed.currentRevision,
            ),
        )
        return confirmed
    }
}
