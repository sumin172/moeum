package com.moeum.kernel

import java.util.UUID

@JvmInline
value class RecordDayId(val value: UUID) {
    companion object { fun generate() = RecordDayId(UuidV7.generate()) }
}
