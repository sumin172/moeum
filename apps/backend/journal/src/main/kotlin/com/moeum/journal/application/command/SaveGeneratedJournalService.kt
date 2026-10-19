
package com.moeum.journal.application.command

import com.moeum.journal.domain.GeneratedJournal
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalId
import com.moeum.kernel.TimeProvider
import java.time.LocalDate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaveGeneratedJournalService(
    private val journalRepository: JournalRepository,
    private val generationJobRepository: GenerationJobRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun save(job: GenerationJob, displayDate: LocalDate, generation: GeneratedJournal): Journal {
        // 작업 행을 먼저 잠가 오래된 워커와 중복 완료를 차단한다. LLM 호출은 이미 종료된 상태다.
        generationJobRepository.lockClaim(job)
        val now = timeProvider.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val journal = journalRepository.insertGenerated(Journal(
            id=JournalId.generate(),userId=job.userId,recordDayId=job.recordDayId,displayDate=displayDate,
            title=generation.title,body=generation.body,version=0,
        ))
        generationJobRepository.save(job.completed(now))
        return journal
    }
}
