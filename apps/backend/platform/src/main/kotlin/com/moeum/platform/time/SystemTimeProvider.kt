package com.moeum.platform.time

import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class SystemTimeProvider : TimeProvider {

    override fun now(): Instant = Instant.now()

    override fun today(zoneId: ZoneId): LocalDate = LocalDate.now(zoneId)
}
