package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.conversation.application.publicapi.MessageSnapshot
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.GenerationJobStatus
import com.moeum.kernel.TimeProvider
import com.moeum.platform.llm.journal.JournalGenerationRequest
import com.moeum.platform.llm.journal.JournalGenerator
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class GenerationExecutor(
    private val generationJobRepository: GenerationJobRepository,
    private val conversationActivityQuery: ConversationActivityQuery,
    private val journalGenerator: JournalGenerator,
    private val saveGeneratedJournalService: SaveGeneratedJournalService,
    private val markGenerationFailedService: MarkGenerationFailedService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(GenerationExecutor::class.java)

    @Scheduled(fixedDelayString = "PT1M")
    fun execute() {
        runCatching { executeDueJobs() }
            .onFailure { e -> log.error("Journal 생성 Execution 실패", e) }
    }

    fun executeDueJobs() {
        val dueJobs = generationJobRepository.findPendingDue(timeProvider.now())
        dueJobs.forEach { job -> executeSingleJob(job) }
    }

    private fun executeSingleJob(job: GenerationJob) {
        val claimed = generationJobRepository.compareAndSetStatus(
            job.id, GenerationJobStatus.PENDING, GenerationJobStatus.PROCESSING,
        )
        if (!claimed) {
            return // 다른 Executor 인스턴스가 먼저 claim
        }

        try {
            val messages = conversationActivityQuery.findMessages(job.userId, job.diaryDate)
            val generation = journalGenerator.generate(
                JournalGenerationRequest(rawTranscript = buildTranscript(messages), localDate = job.diaryDate.toString()),
            )
            saveGeneratedJournalService.save(job, generation)
        } catch (e: Exception) {
            log.error(
                "Journal 생성 실패: jobId={}, userId={}, diaryDate={}, error={}",
                job.id.value,
                job.userId.value,
                job.diaryDate,
                e.message,
                e,
            )
            // markFailed 자체가 실패해도 로그는 남아야 한다.
            runCatching { markGenerationFailedService.markFailed(job, e) }
                .onFailure { markFailure ->
                    log.error("Journal 생성 실패 처리(FAILED 기록)마저 실패: jobId={}", job.id.value, markFailure)
                }
        }
    }
}

private fun buildTranscript(messages: List<MessageSnapshot>): String =
    messages.joinToString("\n") { "[${it.occurredAt}] ${it.role}: ${it.content}" }
