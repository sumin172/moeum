
package com.moeum.journal.infrastructure.jpa

import com.moeum.kernel.RecordDayId
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalId
import com.moeum.kernel.UserId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.sql.Timestamp

@Component
class JournalRepositoryImpl(
    private val jpaRepository: JournalJpaRepository,
    private val jdbc: JdbcTemplate,
) : JournalRepository {
    override fun findByDate(userId: UserId, displayDate: LocalDate): List<Journal> =
        jpaRepository.findByUserIdAndDisplayDateOrderByIdAsc(userId.value, displayDate).map { it.toDomain() }

    override fun findByUserIdAndRecordDayId(userId: UserId, recordDayId: RecordDayId): Journal? =
        jpaRepository.findByUserIdAndRecordDayId(userId.value, recordDayId.value)?.toDomain()

    override fun insertGenerated(journal: Journal): Journal {
        val inserted = jdbc.update("""
            INSERT INTO journal.journals(id,user_id,record_day_id,display_date,title,body,version,
                source_revision,generation_id,model,prompt_version,generated_at)
            VALUES (?,?,?,?,?,?,0,?,?,?,?,?)
            ON CONFLICT (user_id,record_day_id) DO NOTHING
        """.trimIndent(), journal.id.value,journal.userId.value,journal.recordDayId.value,journal.displayDate,
            journal.title,journal.body,journal.sourceRevision,journal.generationId,journal.model,journal.promptVersion,
            Timestamp.from(journal.generatedAt))
        return if (inserted == 1) journal else
            findByUserIdAndRecordDayId(journal.userId,journal.recordDayId) ?: error("충돌한 일기가 없습니다")
    }

    // 사용자 수정만 이 경로로 들어온다. flush 후의 실제 version을 응답한다.
    override fun updateEdited(journal: Journal): Journal =
        jpaRepository.saveAndFlush(journal.toEntity()).toDomain()
}

private fun JournalJpaEntity.toDomain() = Journal(
    JournalId(id),UserId(userId),RecordDayId(recordDayId),displayDate,title,body,version,
    sourceRevision,generationId,model,promptVersion,generatedAt,
)
private fun Journal.toEntity() = JournalJpaEntity(
    id.value,userId.value,recordDayId.value,displayDate,title,body,version,
    sourceRevision,generationId,model,promptVersion,generatedAt,
)
