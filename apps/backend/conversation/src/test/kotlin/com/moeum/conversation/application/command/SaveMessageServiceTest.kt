package com.moeum.conversation.application.command

import com.moeum.conversation.domain.InvalidConversationRequestException
import com.moeum.conversation.domain.model.UserRecordState
import com.moeum.conversation.infrastructure.config.ConversationLimitProperties
import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryRecordDayRepository
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.InMemoryResponseJobRepository
import com.moeum.conversation.support.quotaService
import com.moeum.conversation.domain.AiQuotaExceededException
import com.moeum.platform.job.JobStatus
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class SaveMessageServiceTest {

    private val userId = UserId.generate()
    private val now = Instant.parse("2026-08-02T10:00:00Z")
    private val messageRepository = InMemoryMessageRepository()
    private val recordDayRepository = InMemoryRecordDayRepository()
    private val responseJobRepository = InMemoryResponseJobRepository()
    private val service = newService()

    private fun accepted(result: SaveMessageResult) = result as SaveMessageResult.Accepted

    private fun newService(quotaLimit: Int = 20) =
        SaveMessageService(
            recordDayRepository, messageRepository, responseJobRepository, quotaService(quotaLimit),
            ConversationLimitProperties(maxMessageLength = 10), FixedTimeProvider(now),
        )

    private fun command(occurredAt: String, content: String = "내용", clientMessageId: UUID = UUID.randomUUID()) =
        SaveMessageCommand(clientMessageId, content, Instant.parse(occurredAt), "Asia/Seoul")

    @Test
    fun `메시지는 기본 하루 경계(02시)로 계산한 표시 날짜와 달력 날짜를 함께 저장한다`() {
        // KST 2026-08-02 01:30 — 달력으로는 8/2지만 하루 경계(02:00) 전이라 8/1 하루에 속한다
        val result = accepted(service.save(userId, command("2026-08-01T16:30:00Z")))

        assertThat(result.message.localDate).isEqualTo(LocalDate.of(2026, 8, 2))
        assertThat(result.record.displayDate).isEqualTo(LocalDate.of(2026, 8, 1))
        assertThat(result.message.createdAt).isEqualTo(now)
    }

    @Test
    fun `사용자가 설정한 하루 경계로 표시 날짜를 계산한다`() {
        recordDayRepository.saveState(
            UserRecordState(userId, LocalTime.of(18, 0)),
        )

        val result = accepted(service.save(userId, command("2026-08-01T16:30:00Z")))

        assertThat(result.record.displayDate).isEqualTo(LocalDate.of(2026, 8, 2))
    }

    @Test
    fun `오프라인으로 늦게 도착한 메시지는 발화 시각 기준 하루에 들어간다`() {
        // 저장 시각(now)은 8/2 19:00 KST지만 발화는 7/31 12:00 KST
        val result = accepted(service.save(userId, command("2026-07-31T03:00:00Z")))

        assertThat(result.record.displayDate).isEqualTo(LocalDate.of(2026, 7, 31))
    }

    @Test
    fun `동일한 clientMessageId로 재요청하면 중복 저장하지 않고 기존 메시지를 반환한다`() {
        val clientMessageId = UUID.randomUUID()

        val first = accepted(service.save(userId, command("2026-08-02T03:00:00Z", clientMessageId = clientMessageId)))
        val retried = accepted(service.save(userId, command("2026-08-02T03:00:00Z", clientMessageId = clientMessageId)))

        assertThat(retried.message.id).isEqualTo(first.message.id)
        assertThat(retried.responseJob!!.id).isEqualTo(first.responseJob!!.id)
        assertThat(messageRepository.messages).hasSize(1)
        assertThat(responseJobRepository.jobs).hasSize(1)
    }

    @Test
    fun `동결 이후 신규 입력은 본문 원본 버전 quota 작업 없이 폐기하고 중복은 원래 성공이다`() {
        val usage = com.moeum.conversation.support.InMemoryConversationUsageRepository()
        val service = SaveMessageService(recordDayRepository,messageRepository,responseJobRepository,quotaService(20,usage),
            ConversationLimitProperties(maxMessageLength = 10),FixedTimeProvider(now))
        val command = command("2026-08-01T16:30:00Z")
        val first = accepted(service.save(userId,command))
        recordDayRepository.update(first.record.freeze())
        val counts = usage.counts.toMap()
        val discarded = service.save(userId,command.copy(clientMessageId = UUID.randomUUID()))
        assertThat(discarded).isInstanceOf(SaveMessageResult.Discarded::class.java)
        assertThat(messageRepository.messages).hasSize(1)
        assertThat(responseJobRepository.jobs).isEmpty()
        assertThat(recordDayRepository.days[first.record.id]!!.sourceRevision).isEqualTo(1)
        assertThat(usage.counts).isEqualTo(counts)
        assertThat(accepted(service.save(userId,command)).message.id).isEqualTo(first.message.id)
    }

    @Test
    fun `서버 UTC 기간은 발화의 현지 날짜나 과거 시각으로 바뀌지 않는다`() {
        val usage = com.moeum.conversation.support.InMemoryConversationUsageRepository()
        val service = SaveMessageService(recordDayRepository,messageRepository,responseJobRepository,quotaService(20,usage),
            ConversationLimitProperties(),FixedTimeProvider(now))
        val first = accepted(service.save(userId,command("2026-08-02T10:00:00Z").copy(timezone="Pacific/Kiritimati")))
        assertThat(first.message.localDate).isEqualTo(LocalDate.of(2026,8,3))
        service.save(userId,command("2026-07-31T10:00:00Z"))
        assertThat(usage.counts).containsEntry(userId to LocalDate.of(2026,8,2),2)
        assertThat(usage.counts).hasSize(1)
    }

    @Test
    fun `같은 나노초 발화는 DB 정밀도로 맞춰 원본 버전 순으로 장소를 갱신한다`() {
        val command = command("2026-08-02T10:00:00.123456789Z")
        val first = accepted(service.save(userId,command))
        val second = accepted(service.save(userId,command.copy(clientMessageId = UUID.randomUUID(),timezone = "Asia/Bangkok")))
        assertThat(first.message.occurredAt).isEqualTo(Instant.parse("2026-08-02T10:00:00.123456Z"))
        assertThat(second.record.timezone).isEqualTo("Asia/Bangkok")
        assertThat(second.record.sourceRevision).isEqualTo(2)
    }

    @Test
    fun `content가 비어있으면 저장을 거부한다`() {
        assertThatThrownBy { accepted(service.save(userId, command("2026-08-02T03:00:00Z", content = "   "))) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }

    @Test
    fun `최대 길이를 넘는 메시지는 저장을 거부한다`() {
        assertThatThrownBy { accepted(service.save(userId, command("2026-08-02T03:00:00Z", content = "가".repeat(11)))) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
        assertThat(accepted(service.save(userId, command("2026-08-02T03:00:00Z", content = "가".repeat(10)))).message.content).isEqualTo("가".repeat(10))
    }

    @Test
    fun `timezone이 유효하지 않으면 저장을 거부한다`() {
        val invalid = SaveMessageCommand(UUID.randomUUID(), "내용", Instant.parse("2026-08-02T03:00:00Z"), "Not/AZone")

        assertThatThrownBy { service.save(userId, invalid) }
            .isInstanceOf(InvalidConversationRequestException::class.java)
    }

    @Test
    fun `메시지와 함께 바로 실행할 수 있는 AI 응답 작업을 만든다`() {
        val result = accepted(service.save(userId, command("2026-08-02T03:00:00Z")))

        val job = result.responseJob!!
        assertThat(job.userMessageId).isEqualTo(result.message.id)
        assertThat(job.recordDayId).isEqualTo(result.record.id)
        assertThat(job.state.status).isEqualTo(JobStatus.PENDING)
        assertThat(job.state.nextAttemptAt).isEqualTo(now)
    }

    @Test
    fun `한도 소진 시 새 원본과 응답 작업을 남기지 않는다`() {
        val service = newService(quotaLimit=1)
        val first = accepted(service.save(userId,command("2026-08-02T03:00:00Z")))
        assertThatThrownBy { service.save(userId,command("2026-08-02T03:01:00Z")) }.isInstanceOf(AiQuotaExceededException::class.java)
        assertThat(messageRepository.messages).hasSize(1)
        assertThat(responseJobRepository.jobs).hasSize(1)
        assertThat(recordDayRepository.days[first.record.id]!!.sourceRevision).isEqualTo(1)
    }
}
