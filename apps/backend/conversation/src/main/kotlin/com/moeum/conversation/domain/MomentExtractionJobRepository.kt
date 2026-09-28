package com.moeum.conversation.domain

import com.moeum.conversation.domain.model.MomentExtractionJob

interface MomentExtractionJobRepository {
    fun save(job: MomentExtractionJob): MomentExtractionJob
}
