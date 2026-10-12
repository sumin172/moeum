
package com.moeum.journal.domain.model

import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 생성된 현재 결과 하나. 자동 생성은 INSERT만 하고 version은 사용자 수정 시에만 증가한다. */
data class Journal(
    val id: JournalId,
    val userId: UserId,
    val recordDayId: RecordDayId,
    val displayDate: LocalDate,
    val title: String,
    val body: String,
    val version: Long,
    val sourceRevision: Long,
    val generationId: UUID,
    val model: String,
    val promptVersion: String,
    val generatedAt: Instant,
) {
    fun edited(title: String, body: String): Journal = copy(title = title, body = body)
}
