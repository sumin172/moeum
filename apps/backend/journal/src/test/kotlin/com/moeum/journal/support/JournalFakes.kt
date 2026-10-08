package com.moeum.journal.support

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.JournalRevisionRepository
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalRevision
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant
import java.time.LocalDate

// UNIQUE(user_id, diary_date)와 낙관적 락(version)을 흉내 낸다
class InMemoryJournalRepository : JournalRepository {
    val journals = linkedMapOf<Pair<UserId, LocalDate>, Journal>()

    override fun findByUserIdAndDiaryDate(userId: UserId, diaryDate: LocalDate): Journal? = journals[userId to diaryDate]

    override fun save(journal: Journal): Journal {
        val current = journals[journal.userId to journal.diaryDate]
        if (current != null && current.version != journal.version) {
            throw ObjectOptimisticLockingFailureException(Journal::class.java, journal.id.value)
        }
        val saved = journal.copy(version = if (current == null) journal.version else journal.version + 1)
        journals[journal.userId to journal.diaryDate] = saved
        return saved
    }
}

class InMemoryJournalRevisionRepository : JournalRevisionRepository {
    val revisions = mutableListOf<JournalRevision>()
    override fun append(revision: JournalRevision): JournalRevision = revision.also { revisions += it }
}

class FakeFeatureAccessQuery(var enabled: Set<Feature> = emptySet()) : FeatureAccessQuery {
    override fun isEnabled(userId: UserId, feature: Feature): Boolean = feature in enabled
}

class MutableTimeProvider(var now: Instant) : TimeProvider {
    override fun now(): Instant = now
}
