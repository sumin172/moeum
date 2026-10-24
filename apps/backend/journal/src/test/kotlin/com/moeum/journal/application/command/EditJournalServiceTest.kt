package com.moeum.journal.application.command

import com.moeum.identity.application.publicapi.Feature
import com.moeum.journal.domain.*
import com.moeum.journal.domain.model.*
import com.moeum.journal.support.*
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EditJournalServiceTest {
    private val userId = UserId.generate()
    private val day = RecordDayId.generate()
    private val journals = InMemoryJournalRepository()
    private val features = FakeFeatureAccessQuery()
    private val editService = EditJournalService(journals,features)
    private fun generated() = journals.insertGenerated(Journal(
        JournalId.generate(),userId,day,LocalDate.of(2026,8,2),"산책","한강을 걸었다",0,
    ))
    @Test fun `무료 사용자는 수정할 수 없고 현재 내용은 유지된다`() {
        val journal = generated()
        assertThatThrownBy { editService.edit(userId,day,"제목","본문",journal.version) }.isInstanceOf(JournalEditNotAllowedException::class.java)
        assertThat(journals.findByUserIdAndRecordDayId(userId,day)).isEqualTo(journal)
    }
    @Test fun `구독 수정은 현재 내용과 version만 변경한다`() {
        features.enabled = setOf(Feature.JOURNAL_EDIT)
        val journal = generated()
        val edited = editService.edit(userId,day,"새 제목","새 본문",journal.version)
        assertThat(edited).isEqualTo(journal.copy(title="새 제목",body="새 본문",version=1))
    }
    @Test fun `다른 기기에서 먼저 수정한 version은 충돌이다`() {
        features.enabled = setOf(Feature.JOURNAL_EDIT)
        val journal = generated()
        editService.edit(userId,day,"새 제목","새 본문",journal.version)
        assertThatThrownBy { editService.edit(userId,day,"낡은 제목","낡은 본문",journal.version) }.isInstanceOf(JournalVersionConflictException::class.java)
    }
    @Test fun `빈 제목 본문과 없는 일기는 거부한다`() {
        features.enabled = setOf(Feature.JOURNAL_EDIT)
        assertThatThrownBy { editService.edit(userId,day," ","본문",0) }.isInstanceOf(InvalidJournalRequestException::class.java)
        assertThatThrownBy { editService.edit(userId,day,"제목","본문",0) }.isInstanceOf(JournalNotFoundException::class.java)
    }
}
