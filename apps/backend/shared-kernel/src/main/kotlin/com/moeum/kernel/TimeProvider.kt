package com.moeum.kernel

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

interface TimeProvider {
    fun now(): Instant
    fun today(zoneId: ZoneId): LocalDate
}
