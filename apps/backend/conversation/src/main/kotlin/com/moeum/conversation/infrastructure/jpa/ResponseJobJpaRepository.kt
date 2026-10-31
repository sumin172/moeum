package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ResponseJobJpaRepository : JpaRepository<ResponseJobJpaEntity, UUID> {
    fun findByUserIdAndRecordDayId(userId: UUID, recordDayId: UUID): ResponseJobJpaEntity?
}
