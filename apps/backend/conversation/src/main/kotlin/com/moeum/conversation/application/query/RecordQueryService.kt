package com.moeum.conversation.application.query
import com.moeum.conversation.domain.*
import com.moeum.conversation.domain.model.*
import com.moeum.kernel.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import com.moeum.conversation.infrastructure.config.ResponseJobProperties
import com.moeum.conversation.infrastructure.config.RecordDayProperties
import com.moeum.kernel.TimeProvider
import com.moeum.platform.job.JobRetentionProperties
// 정리된 작업은 job이 null이다. 미응답 여부는 USER의 원본 순번과 answeredSourceRevision으로 판단한다.
data class ResponseState(val job: ResponseJob?, val retryable: Boolean, val answeredSourceRevision: Long)
data class RecordMessages(val record: RecordDay, val messages: List<Message>, val response: ResponseState, val nextCursor: Long?)
@Service
class RecordQueryService(private val records: RecordDayRepository, private val messages: MessageRepository, private val jobs: ResponseJobRepository,
    properties: ResponseJobProperties, private val recordDayProperties: RecordDayProperties,
    private val time: TimeProvider, private val retention: JobRetentionProperties) {
    private val policy = properties.toPolicy()
    @Transactional(readOnly = true)
    fun list(user: UserId, date: LocalDate?, limit: Int) = records.list(user, date, limit)
    // 메시지 페이지·응답 상태·답변 경계를 한 스냅샷에서 읽는다. 조회 중 워커가 완료해도 답변 없는 COMPLETED를 반환하지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(user: UserId, id: RecordDayId, after: Long?, limit: Int): RecordMessages {
        if (after != null && after < 0) throw InvalidConversationRequestException("커서는 0 이상입니다")
        val record = records.find(user,id) ?: throw RecordDayNotFoundException("기록이 없습니다")
        val page = messages.findPage(user,id,after,limit)
        return RecordMessages(record,page,responseState(user, id, jobs.findByRecord(user,id)),page.maxOfOrNull { it.streamSequence } ?: after)
    }
    @Transactional(readOnly = true)
    fun responseState(user: UserId, id: RecordDayId, job: ResponseJob?) =
        ResponseState(job, job?.retryable(policy, time.now(), retention.terminalAge) == true, messages.answeredSourceRevision(user, id))
    @Transactional(readOnly = true)
    fun closeTime(user: UserId): LocalTime = records.findState(user)?.closeTime ?: recordDayProperties.defaultCloseTime
    @Transactional
    fun changeCloseTime(user: UserId, closeTime: LocalTime): LocalTime {
        RecordDay.validateCloseTime(closeTime, recordDayProperties.eveningStart, recordDayProperties.morningEnd)
        records.saveState(records.lockState(user).copy(closeTime = closeTime))
        return closeTime
    }
}
