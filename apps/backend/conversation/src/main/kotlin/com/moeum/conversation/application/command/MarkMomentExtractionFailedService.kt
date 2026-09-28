package com.moeum.conversation.application.command

import com.moeum.conversation.domain.MomentExtractionJobRepository
import com.moeum.conversation.domain.model.MomentExtractionJob
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MarkMomentExtractionFailedService(
    private val momentExtractionJobRepository: MomentExtractionJobRepository,
) {
    @Transactional
    fun markFailed(job: MomentExtractionJob, cause: Throwable) {
        momentExtractionJobRepository.save(job.failed(errorCode = cause::class.simpleName ?: "UNKNOWN"))
    }
}
