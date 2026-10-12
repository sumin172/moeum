package com.moeum.journal.support

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant
import java.time.LocalDate

// UNIQUE(user_id, record_day_id)와 낙관적 락(version)을 흉내 낸다
class InMemoryJournalRepository : JournalRepository {
    override fun findByDate(userId: UserId, displayDate: LocalDate) = journals.values.filter { it.userId == userId && it.displayDate == displayDate }
    val journals = linkedMapOf<Pair<UserId, RecordDayId>, Journal>()

    override fun findByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): Journal? = journals[userId to recordDayId]

    override fun insertGenerated(journal: Journal): Journal =
        journals.getOrPut(journal.userId to journal.recordDayId) { journal }
    override fun updateEdited(journal: Journal): Journal {
        val current = journals[journal.userId to journal.recordDayId]
        if (current == null || current.version != journal.version) {
            throw ObjectOptimisticLockingFailureException(Journal::class.java, journal.id.value)
        }
        if (current == journal) return current
        val saved = journal.copy(version = journal.version + 1)
        journals[journal.userId to journal.recordDayId] = saved
        return saved
    }
}

class FakeFeatureAccessQuery(var enabled: Set<Feature> = emptySet()) : FeatureAccessQuery {
    override fun isEnabled(userId: UserId, feature: Feature): Boolean = feature in enabled
}

class MutableTimeProvider(var now: Instant) : TimeProvider {
    override fun now(): Instant = now
}
