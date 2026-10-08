package com.moeum.journal.domain.model

import com.moeum.kernel.UserId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class JournalLifecycleStatus {
    DRAFT,
    CONFIRMED,

    // 확정 뒤 그 하루에 새 발화가 생김 — 일기가 원본을 다 담지 못한다. 재생성은 V1 범위 밖이고, 사용자는 수정·재확정할 수 있다.
    OUTDATED,
}

data class Journal(
    val id: JournalId,
    val userId: UserId,
    val diaryDate: LocalDate,
    val lifecycleStatus: JournalLifecycleStatus,
    val title: String,
    // 일기 본문. 저장소에서 journals.content(JSONB) {"body"} 구조로 변환한다 — 구조 확장은 Stage 3.
    val body: String,
    val currentRevision: Int,
    // 낙관적 락. 클라이언트는 받은 version을 수정·확정 요청에 그대로 돌려보낸다(다른 기기에서 먼저 바꿨으면 충돌).
    val version: Long,
    // 생성에 쓴 원본 중 가장 큰 유저 메시지 id(UUIDv7, 저장 순서). OUTDATED 판단 기준.
    val sourceLastMessageId: UUID?,
    val confirmedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val purgeAfter: Instant? = null,
) {
    // 사용자가 고친 내용은 다시 확정해야 한다 — 확정·OUTDATED 상태에서 고쳐도 DRAFT로 돌아간다.
    fun edited(title: String, body: String): Journal =
        copy(
            title = title,
            body = body,
            currentRevision = currentRevision + 1,
            lifecycleStatus = JournalLifecycleStatus.DRAFT,
            confirmedAt = null,
        )

    fun confirmed(now: Instant): Journal = copy(lifecycleStatus = JournalLifecycleStatus.CONFIRMED, confirmedAt = now)

    val isConfirmed: Boolean get() = lifecycleStatus == JournalLifecycleStatus.CONFIRMED

    // 확정된 일기가 이 유저 메시지를 담지 못했는지. UUIDv7은 앞자리가 시각이라 비교 순서가 저장 순서와 같다
    // (java.util.UUID.compareTo는 부호 있는 비교지만 v7의 최상위 비트는 서기 6000년대까지 0이다).
    fun misses(userMessageId: UUID): Boolean =
        isConfirmed && (sourceLastMessageId == null || userMessageId > sourceLastMessageId)

    fun outdated(): Journal = copy(lifecycleStatus = JournalLifecycleStatus.OUTDATED)

    companion object {
        fun draft(
            id: JournalId,
            userId: UserId,
            diaryDate: LocalDate,
            title: String,
            body: String,
            sourceLastMessageId: UUID?,
        ): Journal =
            Journal(
                id = id,
                userId = userId,
                diaryDate = diaryDate,
                lifecycleStatus = JournalLifecycleStatus.DRAFT,
                title = title,
                body = body,
                currentRevision = 1,
                version = 0,
                sourceLastMessageId = sourceLastMessageId,
            )
    }
}
