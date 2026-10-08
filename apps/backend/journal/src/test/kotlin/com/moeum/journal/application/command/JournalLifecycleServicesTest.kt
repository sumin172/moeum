package com.moeum.journal.application.command

import com.moeum.identity.application.publicapi.Feature
import com.moeum.journal.application.publicapi.events.JournalConfirmedV1
import com.moeum.journal.domain.InvalidJournalRequestException
import com.moeum.journal.domain.JournalEditNotAllowedException
import com.moeum.journal.domain.JournalNotFoundException
import com.moeum.journal.domain.JournalVersionConflictException
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalId
import com.moeum.journal.domain.model.JournalLifecycleStatus
import com.moeum.journal.domain.model.JournalRevisionEditor
import com.moeum.journal.support.FakeFeatureAccessQuery
import com.moeum.journal.support.InMemoryJournalRepository
import com.moeum.journal.support.InMemoryJournalRevisionRepository
import com.moeum.journal.support.MutableTimeProvider
import com.moeum.kernel.UserId
import com.moeum.kernel.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.time.LocalDate

class JournalLifecycleServicesTest {

    private val userId = UserId.generate()
    private val day = LocalDate.of(2026, 8, 2)
    private val timeProvider = MutableTimeProvider(Instant.parse("2026-08-03T01:00:00Z"))
    private val journals = InMemoryJournalRepository()
    private val revisions = InMemoryJournalRevisionRepository()
    private val features = FakeFeatureAccessQuery()
    private val events = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { events += it }

    private val editService = EditJournalService(journals, revisions, features, timeProvider)
    private val confirmService = ConfirmJournalService(journals, publisher, timeProvider)
    private val outdatedService = MarkJournalOutdatedService(journals)

    private val sourceLast = UuidV7.generate()
    private fun generated(): Journal =
        journals.save(Journal.draft(JournalId.generate(), userId, day, "산책", "한강을 걸었다", sourceLastMessageId = sourceLast))

    @Test
    fun `확정하면 CONFIRMED가 되고 JournalConfirmedV1을 발행한다`() {
        val journal = generated()
        val correlationId = UuidV7.generate()

        val confirmed = confirmService.confirm(userId, day, journal.version, correlationId)

        assertThat(confirmed.lifecycleStatus).isEqualTo(JournalLifecycleStatus.CONFIRMED)
        val event = events.single() as JournalConfirmedV1
        assertThat(event.journalId).isEqualTo(journal.id.value)
        assertThat(event.correlationId).isEqualTo(correlationId)
        assertThat(event.revision).isEqualTo(1)
        assertThat(event.eventType).isEqualTo("JournalConfirmed")
    }

    @Test
    fun `이미 확정된 일기를 다시 확정하면 이벤트를 또 내지 않는다`() {
        val journal = generated()
        val confirmed = confirmService.confirm(userId, day, journal.version, UuidV7.generate())

        confirmService.confirm(userId, day, confirmed.version, UuidV7.generate())

        assertThat(events).hasSize(1)
    }

    @Test
    fun `요청의 version이 낡았으면 충돌로 거부한다`() {
        val journal = generated()
        confirmService.confirm(userId, day, journal.version, UuidV7.generate())

        assertThatThrownBy { confirmService.confirm(userId, day, journal.version, UuidV7.generate()) }
            .isInstanceOf(JournalVersionConflictException::class.java)
    }

    @Test
    fun `일기 수정 기능이 없는 요금제는 고칠 수 없다`() {
        val journal = generated()

        assertThatThrownBy { editService.edit(userId, day, "제목", "본문", journal.version) }
            .isInstanceOf(JournalEditNotAllowedException::class.java)
    }

    @Test
    fun `수정 기능이 있으면 고친 내용을 사용자 리비전으로 남기고 DRAFT로 되돌린다`() {
        features.enabled = setOf(Feature.JOURNAL_EDIT)
        val journal = generated()
        val confirmed = confirmService.confirm(userId, day, journal.version, UuidV7.generate())

        val edited = editService.edit(userId, day, "새 제목", "새 본문", confirmed.version)

        assertThat(edited.lifecycleStatus).isEqualTo(JournalLifecycleStatus.DRAFT)
        val revision = revisions.revisions.single()
        assertThat(revision.editedBy).isEqualTo(JournalRevisionEditor.USER)
        assertThat(revision.revisionNo).isEqualTo(2)
        assertThat(revision.userId).isEqualTo(userId)
    }

    @Test
    fun `빈 제목·본문, 없는 일기는 거부한다`() {
        features.enabled = setOf(Feature.JOURNAL_EDIT)

        assertThatThrownBy { editService.edit(userId, day, " ", "본문", 0) }.isInstanceOf(InvalidJournalRequestException::class.java)
        assertThatThrownBy { editService.edit(userId, day, "제목", "본문", 0) }.isInstanceOf(JournalNotFoundException::class.java)
    }

    @Test
    fun `확정된 일기의 하루에 더 나중 유저 메시지가 생기면 OUTDATED로 바꾼다`() {
        val journal = generated()
        confirmService.confirm(userId, day, journal.version, UuidV7.generate())

        assertThat(outdatedService.markIfMissed(userId, day, sourceLast)).isFalse()
        assertThat(outdatedService.markIfMissed(userId, day, UuidV7.generate())).isTrue()

        assertThat(journals.findByUserIdAndDiaryDate(userId, day)!!.lifecycleStatus).isEqualTo(JournalLifecycleStatus.OUTDATED)
    }
}
