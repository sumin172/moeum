package com.moeum.journal.domain

import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.UserId
import java.time.LocalDate

interface JournalRepository {
    fun findByUserIdAndDiaryDate(userId: UserId, diaryDate: LocalDate): Journal?
    fun save(journal: Journal): Journal
}
