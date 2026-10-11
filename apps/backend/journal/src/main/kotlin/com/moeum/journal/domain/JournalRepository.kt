package com.moeum.journal.domain

import com.moeum.kernel.RecordDayId
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.UserId
import java.time.LocalDate

interface JournalRepository {
    fun findByDate(userId: UserId, displayDate: LocalDate): List<Journal>
    fun findByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): Journal?
    fun save(journal: Journal): Journal
}
