package com.moeum.journal.domain

import com.moeum.kernel.RecordDayId
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.UserId
import java.time.LocalDate

interface JournalRepository {
    fun findByDate(userId: UserId, displayDate: LocalDate): List<Journal>
    fun findByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): Journal?
    /** 생성 결과를 삽입한다. 기록당 하나이며 이미 있으면 유일키 위반(DuplicateKeyException)이다. */
    fun insertGenerated(journal: Journal): Journal
    /** 사용자 수정만 저장하며 version이 다르면 충돌로 거절한다. */
    fun updateEdited(journal: Journal): Journal
}
