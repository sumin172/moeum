package com.moeum.kernel

import java.time.Instant

// 기록 날짜와 마감 시각은 Conversation의 RecordDay가 소유하므로 여기서는 현재 Instant만 제공한다.
interface TimeProvider {
    fun now(): Instant
}
