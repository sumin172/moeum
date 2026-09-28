package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MomentExtractionJobJpaRepository : JpaRepository<MomentExtractionJobJpaEntity, UUID>
