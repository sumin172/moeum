package com.moeum.journal.application.command

import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.journal.domain.InvalidJournalRequestException
import com.moeum.journal.domain.JournalEditNotAllowedException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.JournalRevisionRepository
import com.moeum.journal.domain.JournalVersionConflictException
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalRevision
import com.moeum.journal.domain.model.JournalRevisionEditor
import com.moeum.journal.domain.model.JournalRevisionId
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class EditJournalService(
    private val journalRepository: JournalRepository,
    private val journalRevisionRepository: JournalRevisionRepository,
    private val featureAccessQuery: FeatureAccessQuery,
    private val timeProvider: TimeProvider,
) {
    // 고치면 DRAFT로 돌아가 다시 확정해야 한다. 고친 내용은 리비전(edited_by = USER)으로 남는다.
    @Transactional
    fun edit(userId: UserId, diaryDate: LocalDate, title: String, body: String, expectedVersion: Long): Journal {
        if (!featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT)) {
            throw JournalEditNotAllowedException("일기 수정은 구독 기능입니다: userId=${userId.value}")
        }
        if (title.isBlank() || body.isBlank()) {
            throw InvalidJournalRequestException("제목과 본문은 비어 있을 수 없습니다")
        }
        val journal = journalRepository.findByUserIdAndDiaryDate(userId, diaryDate)
            ?: throw JournalNotFoundException("일기가 없습니다: diaryDate=$diaryDate")
        if (journal.version != expectedVersion) {
            throw JournalVersionConflictException("다른 기기에서 먼저 바뀐 일기입니다: expected=$expectedVersion, actual=${journal.version}")
        }

        // 저장 시점의 경합(같은 version으로 동시에 들어온 요청)은 JPA @Version이 막는다
        val edited = journalRepository.save(journal.edited(title, body))
        journalRevisionRepository.append(
            JournalRevision.of(
                id = JournalRevisionId.generate(),
                journalId = edited.id,
                userId = userId,
                revisionNo = edited.currentRevision,
                title = edited.title,
                body = edited.body,
                editedBy = JournalRevisionEditor.USER,
                now = timeProvider.now(),
            ),
        )
        return edited
    }
}
