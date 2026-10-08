package com.moeum.journal.application.command

import com.moeum.conversation.application.publicapi.ConversationActivityQuery
import com.moeum.journal.domain.DiaryPreferenceRepository
import com.moeum.journal.domain.DiaryWindow
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.calculateDiaryWindow
import com.moeum.journal.domain.model.DiaryPreference
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.TimeProvider
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

private data class PlannedBucket(
    val userId: UserId,
    val timezone: String,
    val generationTime: LocalTime,
    val window: DiaryWindow,
)

@Service
class GenerationPlanner(
    private val conversationActivityQuery: ConversationActivityQuery,
    private val diaryPreferenceRepository: DiaryPreferenceRepository,
    private val generationJobRepository: GenerationJobRepository,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(GenerationPlanner::class.java)

    // from/to는 호출부가 결정한다(Planner가 now()를 스스로 들여다보지 않는다). "최근 활동 빠르게 반영"과
    // "장애로 놓친 것 회수"는 이 함수를 다른 범위로 호출하는 것뿐, 별도 로직이 아니다.
    fun plan(from: Instant, to: Instant, planningType: String) {
        val activities = conversationActivityQuery.findActivities(from, to)
        val generationTimeByUser = activities.map { it.userId }.distinct()
            .associateWith { generationTimeOf(it) }

        val buckets = activities
            .map { activity ->
                val generationTime = generationTimeByUser.getValue(activity.userId)
                PlannedBucket(
                    userId = activity.userId,
                    timezone = activity.timezone,
                    generationTime = generationTime,
                    window = calculateDiaryWindow(activity.occurredAt, activity.timezone, generationTime),
                )
            }
            .distinctBy { it.userId to it.window.diaryDate }

        val createdCount = buckets.count { planJob(it) }

        log.info(
            "Journal 생성 Planning 완료: planningType={}, from={}, to={}, activityCount={}, bucketCount={}, createdCount={}, duplicateCount={}",
            planningType, from, to, activities.size, buckets.size, createdCount, buckets.size - createdCount,
        )
    }

    private fun generationTimeOf(userId: UserId): LocalTime =
        diaryPreferenceRepository.findByUserId(userId)?.generationTime ?: DiaryPreference.DEFAULT_GENERATION_TIME

    // UNIQUE(user_id, diary_date) 충돌은 "이미 계획됨"을 뜻하는 정상 경로다 — 예외가 아니라 흐름 제어로 다룬다.
    private fun planJob(bucket: PlannedBucket): Boolean {
        val job = GenerationJob.pending(
            userId = bucket.userId,
            diaryDate = bucket.window.diaryDate,
            windowStart = bucket.window.windowStart,
            windowEnd = bucket.window.windowEnd,
            scheduledAt = bucket.window.windowEnd,
            timezoneAtScheduling = bucket.timezone,
            generationTimeAtScheduling = bucket.generationTime,
            now = timeProvider.now(),
        )
        return try {
            generationJobRepository.save(job)
            true
        } catch (_: DataIntegrityViolationException) {
            false
        }
    }

    @Scheduled(fixedDelayString = "PT5M")
    fun planRecent() {
        val now = timeProvider.now()
        runCatching { plan(from = now.minus(Duration.ofMinutes(10)), to = now, planningType = "RECENT") }
            .onFailure { e -> log.error("Journal 생성 Planning(RECENT) 실패", e) }
    }

    // 정상 경로(lookback 10분)보다 긴 장애로 놓친 활동을 회수한다. generation_jobs와 별도로 비교하는 diff
    // 로직을 두지 않고, 같은 plan()을 범위만 넓혀 호출한다 — 멱등 insert가 이미 그 역할을 한다.
    @Scheduled(cron = "0 0 4 * * *")
    fun planReconcile() {
        val now = timeProvider.now()
        runCatching { plan(from = now.minus(Duration.ofDays(3)), to = now, planningType = "RECONCILIATION") }
            .onFailure { e -> log.error("Journal 생성 Planning(RECONCILIATION) 실패", e) }
    }
}
