package com.moeum.conversation.infrastructure.jpa

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ResponseJobJpaRepository : JpaRepository<ResponseJobJpaEntity, UUID> {
    fun findByUserMessageId(userMessageId: UUID): ResponseJobJpaEntity?
    fun findAllByUserMessageIdIn(userMessageIds: Collection<UUID>): List<ResponseJobJpaEntity>
}
