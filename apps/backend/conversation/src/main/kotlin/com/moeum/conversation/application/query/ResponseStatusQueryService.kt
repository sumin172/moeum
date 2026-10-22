package com.moeum.conversation.application.query

import com.moeum.conversation.domain.*
import com.moeum.conversation.domain.model.ResponseJobId
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.kernel.UserId
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.moeum.kernel.TimeProvider
import com.moeum.platform.job.JobRetentionProperties

data class ResponseStatus(val job: ResponseJob, val retryable: Boolean)
@Service
class ResponseStatusQueryService(private val jobs: ResponseJobRepository, properties: ResponseJobProperties,
    private val time: TimeProvider, private val retention: JobRetentionProperties) {
    private val policy = properties.toPolicy()
    @Transactional(readOnly=true)
    fun get(user: UserId, ids: List<ResponseJobId>): List<ResponseStatus> {
        if (ids.isEmpty() || ids.size > 100) throw InvalidConversationRequestException("작업 ID는 1~100개입니다")
        val unique = ids.distinct()
        val result = jobs.findAllByUserIdAndIdIn(user,unique)
        if (result.size != unique.size) throw MessageNotFoundException("조회 가능한 응답 작업이 없습니다")
        val now = time.now()
        val retryable = result.filter { it.retryable(policy) && now.isBefore(it.updatedAt.plus(retention.terminalAge)) }.map { it.id }.toSet()
        val superseded = jobs.supersededIds(user,retryable)
        return result.map { ResponseStatus(it,it.id in retryable && it.id !in superseded) }
    }
}
