package com.moeum.journal.application.command

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.JournalRepository
import com.moeum.journal.domain.JournalRevisionRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.Journal
import com.moeum.journal.domain.model.JournalId
import com.moeum.journal.domain.model.JournalRevision
import com.moeum.journal.domain.model.JournalRevisionEditor
import com.moeum.journal.domain.model.JournalRevisionId
import com.moeum.kernel.TimeProvider
import com.moeum.platform.llm.journal.JournalGenerationResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SaveGeneratedJournalService(
    private val journalRepository: JournalRepository,
    private val journalRevisionRepository: JournalRevisionRepository,
    private val generationJobRepository: GenerationJobRepository,
    private val timeProvider: TimeProvider,
) {
    @Transactional
    fun save(job: GenerationJob, generation: JournalGenerationResponse): Journal {
        val now = timeProvider.now()

        val journal = journalRepository.save(
            Journal.draft(
                id = JournalId.generate(),
                userId = job.userId,
                diaryDate = job.diaryDate,
                title = generation.title,
                content = generation.content,
            ),
        )

        journalRevisionRepository.save(
            JournalRevision.of(
                id = JournalRevisionId.generate(),
                journalId = journal.id,
                revisionNo = journal.currentRevision,
                title = journal.title,
                content = journal.content,
                editedBy = JournalRevisionEditor.AI,
                now = now,
            ),
        )

        generationJobRepository.save(
            job.completed(
                journalId = journal.id,
                provider = generation.provider,
                model = generation.model,
                promptVersion = generation.promptVersion,
                inputTokens = generation.inputTokens,
                outputTokens = generation.outputTokens,
                generationId = generation.generationId,
                now = now,
            ),
        )

        return journal
    }
}
