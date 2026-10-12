package com.moeum.kernel

import java.util.UUID

data class UserId(val value: UUID) {
    companion object {
        fun generate(): UserId = UserId(UuidV7.generate())

        fun of(value: String): UserId = UserId(UUID.fromString(value))
    }
}
