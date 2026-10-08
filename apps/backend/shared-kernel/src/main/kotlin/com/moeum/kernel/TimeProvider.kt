package com.moeum.kernel

import java.time.Instant

// "오늘"은 달력 날짜가 아니라 사용자 하루 경계로 정해지므로(conversation의 DayPreference) 여기서 제공하지 않는다.
interface TimeProvider {
    fun now(): Instant
}
