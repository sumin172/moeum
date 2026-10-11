package com.moeum.journal.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class JournalTest {

    private val now = Instant.parse("2026-08-03T00:00:00Z")
    private fun draft() =
        Journal.draft(JournalId.generate(), UserId.generate(), RecordDayId.generate(), LocalDate.of(2026, 8, 2), "제목", "본문", sourceRevision = 1)

    @Test
    fun `확정한 일기를 고치면 리비전이 오르고 다시 확정해야 하는 DRAFT로 돌아간다`() {
        val edited = draft().confirmed(now).edited("새 제목", "새 본문")

        assertThat(edited.lifecycleStatus).isEqualTo(JournalLifecycleStatus.DRAFT)
        assertThat(edited.currentRevision).isEqualTo(2)
        assertThat(edited.confirmedAt).isNull()
        assertThat(edited.body).isEqualTo("새 본문")
    }

}
