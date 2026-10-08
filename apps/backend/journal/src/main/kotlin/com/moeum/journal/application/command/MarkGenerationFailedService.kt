package com.moeum.journal.application.command

import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MarkGenerationFailedService(
    private val generationJobRepository: GenerationJobRepository,
) {
    @Transactional
    fun markFailed(job: GenerationJob, cause: Throwable) {
        generationJobRepository.save(job.failed(errorCode = cause::class.simpleName ?: "UNKNOWN"))
    }
}
