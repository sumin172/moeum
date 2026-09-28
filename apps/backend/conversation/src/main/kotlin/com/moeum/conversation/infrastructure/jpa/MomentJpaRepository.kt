package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MomentJpaRepository : JpaRepository<MomentJpaEntity, UUID> {
    fun findAllByMomentSetId(momentSetId: UUID): List<MomentJpaEntity>
}
