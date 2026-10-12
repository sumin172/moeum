package com.moeum.journal.application.query
import com.moeum.identity.application.publicapi.*
import com.moeum.journal.domain.*
import com.moeum.journal.domain.model.*
import com.moeum.kernel.*
import org.springframework.stereotype.Service
data class JournalDay(val recordDayId: RecordDayId, val journal: Journal?, val generationJob: GenerationJob?, val editable: Boolean)
@Service
class GetJournalService(private val journalRepository: JournalRepository, private val generationJobRepository: GenerationJobRepository,
    private val featureAccessQuery: FeatureAccessQuery) {
    fun list(userId: UserId, date: java.time.LocalDate) = journalRepository.findByDate(userId, date)
    fun getDay(userId: UserId, recordDayId: RecordDayId): JournalDay =
        JournalDay(recordDayId, journalRepository.findByUserIdAndRecordDayId(userId, recordDayId),
            generationJobRepository.findByUserIdAndRecordDayId(userId, recordDayId), featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT))
}
