package com.moeum.conversation.application.command

import com.moeum.conversation.domain.AiUsageRepository
import com.moeum.conversation.infrastructure.config.AiQuotaProperties
import com.moeum.kernel.UserId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

// 유저당 하루 AI 응답 요청 수 상한(비용 폭주 방지 안전장치). 새 메시지와 사용자 재시도 요청마다 1씩 쓴다 —
// 서버 쪽 자동 재시도는 사용자 책임이 아니므로 세지 않는다.
@Component
class AiQuotaGuard(
    private val aiUsageRepository: AiUsageRepository,
    private val aiQuotaProperties: AiQuotaProperties,
) {
    private val log = LoggerFactory.getLogger(AiQuotaGuard::class.java)

    fun tryConsume(userId: UserId, dayDate: LocalDate): Boolean {
        val count = aiUsageRepository.recordAttempt(userId, dayDate)
        val allowed = count <= aiQuotaProperties.dailyMessageLimit
        if (!allowed) {
            log.warn(
                "일일 AI 응답 quota 초과: userId={}, dayDate={}, count={}, limit={}",
                userId.value, dayDate, count, aiQuotaProperties.dailyMessageLimit,
            )
        }
        return allowed
    }
}
