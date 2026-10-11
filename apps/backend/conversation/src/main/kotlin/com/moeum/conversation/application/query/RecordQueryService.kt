package com.moeum.conversation.application.query
import com.moeum.conversation.domain.*
import com.moeum.conversation.domain.model.*
import com.moeum.kernel.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
data class RecordMessages(val record: RecordDay, val messages: List<Message>, val responseJobs: Map<MessageId, ResponseJob>)
@Service
class RecordQueryService(private val records: RecordDayRepository, private val messages: MessageRepository, private val jobs: ResponseJobRepository) {
    @Transactional(readOnly = true)
    fun list(user: UserId, date: LocalDate?, limit: Int) = records.list(user, date, limit)
    @Transactional(readOnly = true)
    fun get(user: UserId, id: RecordDayId, after: MessageId?, limit: Int): RecordMessages {
        val record = records.find(user,id) ?: throw RecordDayNotFoundException("기록이 없습니다")
        val page = messages.findPage(user,id,after,limit)
        val responses = jobs.findAllByUserIdAndUserMessageIdIn(user,page.filter { it.role == MessageRole.USER }.map { it.id }).associateBy { it.userMessageId }
        return RecordMessages(record,page,responses)
    }
    @Transactional(readOnly = true)
    fun closeTime(user: UserId): LocalTime = records.findState(user)?.closeTime ?: RecordDay.DEFAULT_CLOSE_TIME
    @Transactional
    fun changeCloseTime(user: UserId, closeTime: LocalTime): LocalTime {
        records.saveState(records.lockState(user).copy(closeTime = closeTime))
        return closeTime
    }
}
