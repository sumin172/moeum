package com.moeum.kernel

import java.util.UUID

data class UserId(val value: UUID) {
    companion object {
        fun generate(): UserId = UserId(UUID.randomUUID())
        fun of(value: String): UserId = UserId(UUID.fromString(value))
    }
}
