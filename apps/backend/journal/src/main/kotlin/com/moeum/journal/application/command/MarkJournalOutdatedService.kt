package com.moeum.journal.application.command

import com.moeum.journal.domain.JournalRepository
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

// 확정된 일기의 하루에 일기가 담지 못한 새 유저 메시지가 생겼으면 OUTDATED로 바꾼다.
// conversation이 journal을 알 수 없으므로(단방향 의존) 이벤트 구독 대신 Planner가 "새 메시지가 생긴 하루"를 볼 때 함께 판단한다 —
// 반영까지 최대 Planner 주기(5분)가 걸린다.
@Service
class MarkJournalOutdatedService(
    private val journalRepository: JournalRepository,
) {
    private val log = LoggerFactory.getLogger(MarkJournalOutdatedService::class.java)

    @Transactional
    fun markIfMissed(userId: UserId, diaryDate: LocalDate, lastUserMessageId: UUID): Boolean {
        val journal = journalRepository.findByUserIdAndDiaryDate(userId, diaryDate) ?: return false
        if (!journal.misses(lastUserMessageId)) return false
        journalRepository.save(journal.outdated())
        log.info("확정 후 새 메시지가 생겨 일기를 OUTDATED로 전환: userId={}, diaryDate={}", userId.value, diaryDate)
        return true
    }
}
