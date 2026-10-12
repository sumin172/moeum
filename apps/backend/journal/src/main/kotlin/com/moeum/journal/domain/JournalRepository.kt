package com.moeum.journal.domain

import com.moeum.kernel.RecordDayId
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.UserId
import java.time.LocalDate

interface JournalRepository {
    fun findByDate(userId: UserId, displayDate: LocalDate): List<Journal>
    fun findByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): Journal?
    /** 최초 결과만 삽입한다. 이미 있으면 현재 결과를 그대로 반환한다. */
    fun insertGenerated(journal: Journal): Journal
    /** 사용자 수정만 저장하며 version이 다르면 충돌로 거절한다. */
    fun updateEdited(journal: Journal): Journal
}
