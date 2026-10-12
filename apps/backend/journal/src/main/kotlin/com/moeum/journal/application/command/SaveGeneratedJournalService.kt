
package com.moeum.journal.application.command

import com.moeum.journal.domain.GeneratedJournal
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalId
import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaveGeneratedJournalService(
    private val journalRepository: JournalRepository,
    private val generationJobRepository: GenerationJobRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun save(job: GenerationJob, generation: GeneratedJournal, sourceRevision: Long): Journal {
        // 작업 행을 먼저 잠가 오래된 워커와 중복 완료를 차단한다. LLM 호출은 이미 종료된 상태다.
        generationJobRepository.lockClaim(job)
        val now = timeProvider.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        val journal = journalRepository.insertGenerated(Journal(
            id=JournalId.generate(),userId=job.userId,recordDayId=job.recordDayId,displayDate=job.displayDate,
            title=generation.title,body=generation.body,version=0,sourceRevision=sourceRevision,
            generationId=generation.generationId,model=generation.model,promptVersion=generation.promptVersion,generatedAt=now,
        ))
        // 같은 기록의 입력은 이미 동결됐다. 다른 입력 버전의 결과를 기존 결과로 가장하지 않는다.
        check(journal.sourceRevision == sourceRevision) { "일기 생성 입력 버전이 기존 결과와 다릅니다" }
        generationJobRepository.save(job.completed(
            journalId=journal.id,provider=generation.provider,model=journal.model,promptVersion=journal.promptVersion,
            inputTokens=generation.inputTokens,outputTokens=generation.outputTokens,generationId=journal.generationId,now=now,
        ))
        return journal
    }
}
