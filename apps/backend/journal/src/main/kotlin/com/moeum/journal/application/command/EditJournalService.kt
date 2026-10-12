package com.moeum.journal.application.command

import com.moeum.kernel.RecordDayId
import com.moeum.identity.application.publicapi.Feature
import com.moeum.identity.application.publicapi.FeatureAccessQuery
import com.moeum.journal.domain.InvalidJournalRequestException
import com.moeum.journal.domain.JournalEditNotAllowedException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.JournalVersionConflictException
import com.moeum.journal.domain.model.Journal
import com.moeum.kernel.UserId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class EditJournalService(
    private val journalRepository: JournalRepository,
    private val featureAccessQuery: FeatureAccessQuery,
) {
    // 구독 권한과 클라이언트 version을 검증하고 현재 내용만 수정한다.
    @Transactional
    fun edit(userId: UserId, recordDayId: RecordDayId, title: String, body: String, expectedVersion: Long): Journal {
        if (!featureAccessQuery.isEnabled(userId, Feature.JOURNAL_EDIT)) {
            throw JournalEditNotAllowedException("일기 수정은 구독 기능입니다: userId=${userId.value}")
        }
        if (title.isBlank() || body.isBlank()) {
            throw InvalidJournalRequestException("제목과 본문은 비어 있을 수 없습니다")
        }
        val journal = journalRepository.findByUserIdAndRecordDayId(userId, recordDayId)
            ?: throw JournalNotFoundException("일기가 없습니다: recordDayId=${recordDayId.value}")
        if (journal.version != expectedVersion) {
            throw JournalVersionConflictException("다른 기기에서 먼저 바뀐 일기입니다: expected=$expectedVersion, actual=${journal.version}")
        }

        // 저장 시점의 경합(같은 version으로 동시에 들어온 요청)은 JPA @Version이 막는다
        return journalRepository.updateEdited(journal.edited(title, body))
    }
}
