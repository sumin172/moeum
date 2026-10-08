package com.moeum.conversation.domain.model

import com.moeum.kernel.UuidV7
import java.util.UUID

data class ResponseJobId(val value: UUID) {
    companion object {
        fun generate(): ResponseJobId = ResponseJobId(UuidV7.generate())
    }
}
