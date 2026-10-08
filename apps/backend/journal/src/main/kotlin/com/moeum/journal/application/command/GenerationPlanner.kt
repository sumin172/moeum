package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.ActiveDay
import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.TimeProvider
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class GenerationPlanner(
    private val conversationActivityQuery: ConversationActivityQuery,
    private val generationJobRepository: GenerationJobRepository,
    private val markJournalOutdatedService: MarkJournalOutdatedService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(GenerationPlanner::class.java)

    // from/to는 호출부가 결정한다(Planner가 now()를 스스로 들여다보지 않는다). "최근 활동 빠르게 반영"과
    // "장애로 놓친 것 회수"는 이 함수를 다른 범위로 호출하는 것뿐, 별도 로직이 아니다.
    // 하루의 경계는 Conversation이 메시지 저장 시점에 이미 정했다 — Planner는 그 하루마다 Job을 만들 뿐이다.
    fun plan(from: Instant, to: Instant, planningType: String) {
        val activeDays = conversationActivityQuery.findActiveDays(from, to)
        val createdCount = activeDays.count { planJob(it) }
        // 이미 확정된 하루에 새 발화가 생겼는지도 같은 관측으로 판단한다(Job은 그대로 두고 일기만 OUTDATED)
        activeDays.forEach { day ->
            day.lastUserMessageId?.let { markJournalOutdatedService.markIfMissed(day.userId, day.dayDate, it) }
        }

        log.info(
            "Journal 생성 Planning 완료: planningType={}, from={}, to={}, dayCount={}, createdCount={}, duplicateCount={}",
            planningType, from, to, activeDays.size, createdCount, activeDays.size - createdCount,
        )
    }

    // UNIQUE(user_id, diary_date) 충돌은 "이미 계획됨"을 뜻하는 정상 경로다 — 예외가 아니라 흐름 제어로 다룬다.
    private fun planJob(day: ActiveDay): Boolean {
        val job = GenerationJob.pending(
            userId = day.userId,
            diaryDate = day.dayDate,
            dayEnd = day.dayEnd,
            now = timeProvider.now(),
        )
        return try {
            generationJobRepository.save(job)
            true
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }

    // 정상 경로: 스케줄 주기(5분)보다 lookback을 넉넉히 겹치게 잡아 자체 유실을 막는다.
    fun planRecent() {
        val now = timeProvider.now()
        plan(from = now.minus(Duration.ofMinutes(10)), to = now, planningType = "RECENT")
    }

    // 정상 경로(lookback 10분)보다 긴 장애로 놓친 활동을 회수한다. generation_jobs와 별도로 비교하는 diff
    // 로직을 두지 않고, 같은 plan()을 범위만 넓혀 호출한다 — 멱등 insert가 이미 그 역할을 한다.
    fun planReconcile() {
        val now = timeProvider.now()
        plan(from = now.minus(Duration.ofDays(3)), to = now, planningType = "RECONCILIATION")
    }
}
