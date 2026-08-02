package com.moeum.kernel

import java.security.SecureRandom
import java.util.UUID

data class UserId(val value: UUID) {
    companion object {
        private val random = SecureRandom()

        fun generate(): UserId = UserId(generateUuidV7())

        fun of(value: String): UserId = UserId(UUID.fromString(value))

        // RFC 9562 UUID v7: 48비트 밀리초 타임스탬프 + 버전(4비트=0111) + 랜덤(12비트) | 변형(2비트=10) + 랜덤(62비트).
        // 삽입 지역성(B-tree 성능)을 얻으면서도 다음 값 추측은 여전히 불가능하다.
        // Kotlin stdlib의 Uuid.generateV7()은 2.3부터 지원돼(프로젝트는 2.1.20) 직접 구현한다.
        private fun generateUuidV7(): UUID {
            val unixTsMs = System.currentTimeMillis() and 0xFFFFFFFFFFFFL
            val randA = random.nextInt(1 shl 12).toLong()
            val mostSigBits = (unixTsMs shl 16) or (0x7L shl 12) or randA

            val randBBytes = ByteArray(8)
            random.nextBytes(randBBytes)
            var leastSigBits = 0L
            for (byte in randBBytes) {
                leastSigBits = (leastSigBits shl 8) or (byte.toLong() and 0xFF)
            }
            leastSigBits = (leastSigBits and ((1L shl 62) - 1)) or (1L shl 63)

            return UUID(mostSigBits, leastSigBits)
        }
    }
}
