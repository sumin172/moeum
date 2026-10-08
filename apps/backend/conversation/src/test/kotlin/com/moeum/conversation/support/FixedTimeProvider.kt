package com.moeum.conversation.support

import com.moeum.kernel.TimeProvider
import java.time.Instant

class FixedTimeProvider(var fixedNow: Instant) : TimeProvider {
    override fun now(): Instant = fixedNow
}
