package com.moeum.conversation.application.query
import com.moeum.conversation.domain.*
import com.moeum.conversation.domain.model.*
import com.moeum.kernel.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
data class RecordMessages(val record: RecordDay, val messages: List<Message>, val responseJobs: Map<MessageId, ResponseJob>, val nextCursor: Long?, val retryableIds: Set<ResponseJobId>)
@Service
class RecordQueryService(private val records: RecordDayRepository, private val messages: MessageRepository, private val jobs: ResponseJobRepository,
    properties: ResponseJobProperties) {
    private val policy = properties.toPolicy()
    @Transactional(readOnly = true)
    fun list(user: UserId, date: LocalDate?, limit: Int) = records.list(user, date, limit)
    @Transactional(readOnly = true)
    fun get(user: UserId, id: RecordDayId, after: Long?, limit: Int): RecordMessages {
        if (after != null && after < 0) throw InvalidConversationRequestException("커서는 0 이상입니다")
        val record = records.find(user,id) ?: throw RecordDayNotFoundException("기록이 없습니다")
        val page = messages.findPage(user,id,after,limit)
        val responses = jobs.findAllByUserIdAndUserMessageIdIn(user,page.filter { it.role == MessageRole.USER }.map { it.id }).associateBy { it.userMessageId }
        val candidates = responses.values.filter { it.retryable(policy) }.map { it.id }
        val superseded = jobs.supersededIds(user,candidates)
        return RecordMessages(record,page,responses,page.maxOfOrNull { it.streamSequence } ?: after,(candidates.toSet() - superseded))
    }
    @Transactional(readOnly = true)
    fun closeTime(user: UserId): LocalTime = records.findState(user)?.closeTime ?: RecordDay.DEFAULT_CLOSE_TIME
    @Transactional
    fun changeCloseTime(user: UserId, closeTime: LocalTime): LocalTime {
        records.saveState(records.lockState(user).copy(closeTime = closeTime))
        return closeTime
    }
}
