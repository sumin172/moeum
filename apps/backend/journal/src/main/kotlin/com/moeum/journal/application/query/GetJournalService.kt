package com.moeum.journal.application.query

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import java.time.LocalDate

data class JournalDay(
    val diaryDate: LocalDate,
    // 아직 생성되지 않았으면 null
    val journal: Journal?,
    // 그 하루에 대화가 없어 생성 작업도 없으면 null
    val generationJob: GenerationJob?,
    // 이 사용자가 일기를 직접 고칠 수 있는지(요금제)
    val editable: Boolean,
)

@Service
class GetJournalService(
    private val journalRepository: JournalRepository,
    private val generationJobRepository: GenerationJobRepository,
    private val featureAccessQuery: FeatureAccessQuery,
) {
    fun getDay(userId: UserId, diaryDate: LocalDate): JournalDay =
        JournalDay(
            diaryDate = diaryDate,
            journal = journalRepository.findByUserIdAndDiaryDate(userId, diaryDate),
            generationJob = generationJobRepository.findByUserIdAndDiaryDate(userId, diaryDate),
            editable = featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT),
        )
}
