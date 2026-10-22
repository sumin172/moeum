package com.moeum.conversation.infrastructure.config

import com.moeum.conversation.domain.model.RecordDay
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalTime

@ConfigurationProperties(prefix = "moeum.conversation.record-day")
data class RecordDayProperties(
    val eveningStart: LocalTime = LocalTime.of(18, 0),
    val morningEnd: LocalTime = LocalTime.of(4, 0),
    val defaultCloseTime: LocalTime = RecordDay.DEFAULT_CLOSE_TIME,
) {
    init {
        require(eveningStart > morningEnd) { "마감 설정 범위는 자정을 가로질러야 합니다" }
        require(defaultCloseTime >= eveningStart || defaultCloseTime <= morningEnd) { "기본 마감 시각이 설정 가능 범위를 벗어났습니다" }
    }
}
