package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ResponseJobJpaRepository : JpaRepository<ResponseJobJpaEntity, UUID> {
    fun findByUserIdAndUserMessageId(userId: UUID, userMessageId: UUID): ResponseJobJpaEntity?
    fun findAllByUserIdAndUserMessageIdIn(userId: UUID, userMessageIds: Collection<UUID>): List<ResponseJobJpaEntity>
}
