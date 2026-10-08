package com.moeum.conversation.application.query

import com.moeum.conversation.support.FixedTimeProvider
import com.moeum.conversation.support.InMemoryDayPreferenceRepository
import com.moeum.conversation.support.InMemoryMessageRepository
import com.moeum.conversation.support.InMemoryResponseJobRepository
import com.moeum.conversation.domain.model.ResponseJob
import com.moeum.conversation.support.userMessage
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class GetTodayConversationServiceTest {

    private val userId = UserId.generate()
    private val messageRepository = InMemoryMessageRepository()
    private val timeProvider = FixedTimeProvider(Instant.parse("2026-08-02T10:00:00Z")) // KST 8/2 19:00
    private val responseJobRepository = InMemoryResponseJobRepository()
    private val service = GetTodayConversationService(InMemoryDayPreferenceRepository(), messageRepository, responseJobRepository, timeProvider)
    private val today = LocalDate.of(2026, 8, 2)

    @Test
    fun `오늘 대화가 없으면 빈 목록을 반환한다`() {
        val result = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 50)

        assertThat(result.dayDate).isEqualTo(today)
        assertThat(result.messages).isEmpty()
    }

    @Test
    fun `오늘은 달력 날짜가 아니라 하루 경계로 정한다`() {
        timeProvider.fixedNow = Instant.parse("2026-08-02T16:30:00Z") // KST 8/3 01:30, 경계(02:00) 전

        val result = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 50)

        assertThat(result.dayDate).isEqualTo(today)
    }

    @Test
    fun `오늘 하루의 메시지를 발화 순서대로 반환한다`() {
        messageRepository.append(userMessage(userId, "나중 메시지", Instant.parse("2026-08-02T09:00:00Z"), today))
        messageRepository.append(userMessage(userId, "먼저 메시지", Instant.parse("2026-08-02T01:00:00Z"), today))
        messageRepository.append(userMessage(UserId.generate(), "다른 사용자", Instant.parse("2026-08-02T02:00:00Z"), today))

        val result = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 50)

        assertThat(result.messages).extracting("content").containsExactly("먼저 메시지", "나중 메시지")
    }

    @Test
    fun `previousDay가 true이면 전날 하루를 반환한다`() {
        val yesterday = today.minusDays(1)
        messageRepository.append(userMessage(userId, "어제 메시지", Instant.parse("2026-08-01T09:00:00Z"), yesterday))

        val result = service.getToday(userId, "Asia/Seoul", previousDay = true, after = null, limit = 50)

        assertThat(result.dayDate).isEqualTo(yesterday)
        assertThat(result.messages).extracting("content").containsExactly("어제 메시지")
    }

    @Test
    fun `커서 없이 조회하면 최근 limit개를 반환하고, after 커서로 그 이후 새 메시지만 받아온다`() {
        (1..4).forEach { i ->
            messageRepository.append(userMessage(userId, "메시지$i", Instant.parse("2026-08-02T0$i:00:00Z"), today))
        }

        val firstPage = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 2)
        assertThat(firstPage.messages).extracting("content").containsExactly("메시지3", "메시지4")

        messageRepository.append(userMessage(userId, "메시지5", Instant.parse("2026-08-02T05:00:00Z"), today))

        val delta = service.getToday(userId, "Asia/Seoul", previousDay = false, after = firstPage.messages.last().id, limit = 2)
        assertThat(delta.messages).extracting("content").containsExactly("메시지5")
    }

    @Test
    fun `유저 메시지마다 AI 응답 작업 상태를 함께 돌려준다`() {
        val message = messageRepository.append(userMessage(userId, "질문", Instant.parse("2026-08-02T01:00:00Z"), today))
        val job = responseJobRepository.save(ResponseJob.pending(message, Instant.parse("2026-08-02T01:00:00Z")))

        val result = service.getToday(userId, "Asia/Seoul", previousDay = false, after = null, limit = 50)

        assertThat(result.responseJobs[message.id]).isEqualTo(job)
    }
}
