package com.moeum.platform.time

import com.moeum.kernel.TimeProvider
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class SystemTimeProvider : TimeProvider {

    override fun now(): Instant = Instant.now()
}
