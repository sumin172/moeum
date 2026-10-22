package com.moeum.journal.application.query
import com.moeum.identity.application.publicapi.*
import com.moeum.journal.domain.*
import com.moeum.journal.domain.model.*
import com.moeum.kernel.*
import com.moeum.platform.job.JobRetentionProperties
import com.moeum.platform.job.JobStatus
import org.springframework.stereotype.Service
data class JournalDay(val recordDayId: RecordDayId, val journal: Journal?, val generationJob: GenerationJob?, val editable: Boolean,
    val generationRetryable: Boolean)
@Service
class GetJournalService(private val journalRepository: JournalRepository, private val generationJobRepository: GenerationJobRepository,
    private val featureAccessQuery: FeatureAccessQuery, private val time: TimeProvider,
    private val retention: JobRetentionProperties) {
    fun list(userId: UserId, date: java.time.LocalDate) = journalRepository.findByDate(userId, date)
    fun getDay(userId: UserId, recordDayId: RecordDayId): JournalDay {
        val job = generationJobRepository.findByUserIdAndRecordDayId(userId, recordDayId)
        return JournalDay(recordDayId, journalRepository.findByUserIdAndRecordDayId(userId, recordDayId), job,
            featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT),
            job?.state?.status == JobStatus.FAILED && time.now().isBefore(job.createdAt.plus(retention.terminalAge)))
    }
}
