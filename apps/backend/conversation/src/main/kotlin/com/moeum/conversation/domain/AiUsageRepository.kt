package com.moeum.conversation.domain

import com.moeum.kernel.UserId
import java.time.LocalDate

interface AiUsageRepository {
    /** 유저×날짜 단위로 호출 시도 횟수를 원자적으로 1 증가시키고, 증가 후의 카운트를 반환한다. */
    fun recordAttempt(userId: UserId, date: LocalDate): Int
}
