package com.moeum.conversation.domain.model

import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ConversationDayTest {

    @Test
    fun `open하면 해당 timezone 기준 다음날 자정이 closesAt으로 계산된다`() {
        val day = ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = UserId.generate(),
            localDate = LocalDate.of(2026, 8, 2),
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-02T01:00:00Z"),
        )

        // 2026-08-03T00:00:00+09:00 == 2026-08-02T15:00:00Z
        assertThat(day.closesAt).isEqualTo(Instant.parse("2026-08-02T15:00:00Z"))
    }

    @Test
    fun `메시지 추가로 timezone이 바뀌면 closesAt이 새 timezone 기준으로 재계산된다`() {
        val day = ConversationDay.open(
            id = ConversationDayId.generate(),
            userId = UserId.generate(),
            localDate = LocalDate.of(2026, 8, 2),
            timezone = "Asia/Seoul",
            now = Instant.parse("2026-08-02T01:00:00Z"),
        )

        val updated = day.withMessageAdded(timezone = "UTC")

        // 2026-08-03T00:00:00Z
        assertThat(updated.closesAt).isEqualTo(Instant.parse("2026-08-03T00:00:00Z"))
        assertThat(updated.timezone).isEqualTo("UTC")
        assertThat(updated.sourceRevision).isEqualTo(1)
    }
}
