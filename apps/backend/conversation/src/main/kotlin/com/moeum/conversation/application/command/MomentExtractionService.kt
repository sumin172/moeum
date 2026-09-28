package com.moeum.conversation.application.command

import com.moeum.conversation.domain.ConversationDayRepository
import com.moeum.conversation.domain.MessageRepository
import com.moeum.conversation.domain.MomentExtractionJobRepository
import com.moeum.conversation.domain.MomentRepository
import com.moeum.conversation.domain.MomentSetRepository
import com.moeum.conversation.domain.model.ConversationDayId
import com.moeum.conversation.domain.model.Message
import com.moeum.conversation.domain.model.Moment
import com.moeum.conversation.domain.model.MomentExtractionJob
import com.moeum.kernel.TimeProvider
import com.moeum.platform.llm.moment.MomentExtractionRequest
import com.moeum.platform.llm.moment.MomentExtractor
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

// Insight 배치나 아카이브 드릴다운처럼 Moment를 실제로 쓰는 기능이 필요한 시점에 호출한다.
// 구독 게이팅 등은 호출부의 책임이다. Conversation 모듈 내부에서 conversation.messages를
// 그 자리에서 다시 읽는다 — 같은 모듈이라 언제 호출되든 항상 최신 원본을 볼 수 있다.
@Service
class MomentExtractionService(
    private val conversationDayRepository: ConversationDayRepository,
    private val messageRepository: MessageRepository,
    private val momentSetRepository: MomentSetRepository,
    private val momentRepository: MomentRepository,
    private val momentExtractionJobRepository: MomentExtractionJobRepository,
    private val momentExtractor: MomentExtractor,
    private val saveMomentExtractionResultService: SaveMomentExtractionResultService,
    private val markMomentExtractionFailedService: MarkMomentExtractionFailedService,
    private val timeProvider: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(MomentExtractionService::class.java)

    // 이미 같은 revision으로 추출된 적 있으면 그 결과를 그대로 반환한다(멱등, 재호출 안전).
    fun ensureExtracted(conversationDayId: ConversationDayId): List<Moment> {
        val day = conversationDayRepository.findById(conversationDayId)
            ?: error("ConversationDay를 찾을 수 없습니다: id=${conversationDayId.value}")

        momentSetRepository.findByConversationDayIdAndSourceRevision(conversationDayId, day.sourceRevision)?.let {
            return momentRepository.findAllByMomentSetId(it.id)
        }

        val job = MomentExtractionJob.pending(conversationDayId, day.sourceRevision, timeProvider.now())
        try {
            momentExtractionJobRepository.save(job)
        } catch (_: DataIntegrityViolationException) {
            log.info("이미 처리 중인 Moment 추출 Job, 스킵: requestKey={}", job.requestKey)
            return emptyList()
        }

        return try {
            val messages = messageRepository.findAllByConversationDayId(conversationDayId)
            val extraction = momentExtractor.extract(
                MomentExtractionRequest(rawTranscript = buildTranscript(messages), localDate = day.localDate.toString()),
            )
            saveMomentExtractionResultService.save(conversationDayId, day.userId, day.localDate, day.sourceRevision, job, extraction)
        } catch (e: Exception) {
            log.error(
                "Moment 추출 실패: conversationDayId={}, requestKey={}, error={}",
                conversationDayId.value,
                job.requestKey,
                e.message,
                e,
            )
            // markFailed 자체가 실패해도 로그는 남아야 한다.
            runCatching { markMomentExtractionFailedService.markFailed(job, e) }
                .onFailure { markFailure ->
                    log.error("Moment 추출 실패 처리(FAILED 기록)마저 실패: requestKey={}", job.requestKey, markFailure)
                }
            emptyList()
        }
    }
}

private fun buildTranscript(messages: List<Message>): String =
    messages.joinToString("\n") { "[${it.occurredAt}] ${it.role}: ${it.content}" }
