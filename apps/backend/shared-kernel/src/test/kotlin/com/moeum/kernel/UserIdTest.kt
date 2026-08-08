package com.moeum.kernel

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserIdTest {

    @Test
    fun `생성된 UUID는 버전 7, 변형 2(IETF)를 따른다`() {
        val userId = UserId.generate()

        assertThat(userId.value.version()).isEqualTo(7)
        assertThat(userId.value.variant()).isEqualTo(2)
    }

    @Test
    fun `타임스탬프 상위 48비트가 생성 시각과 일치한다`() {
        val before = System.currentTimeMillis()
        val userId = UserId.generate()
        val after = System.currentTimeMillis()

        val embeddedTsMs = userId.value.mostSignificantBits ushr 16

        assertThat(embeddedTsMs).isBetween(before, after)
    }

    @Test
    fun `같은 generator 인스턴스에서 연속 생성한 UUID는 타임스탬프 필드 기준 비내림차순이다`() {
        val ids = (1..100).map { UserId.generate().value }

        for (i in 1 until ids.size) {
            val prevMsb = ids[i - 1].mostSignificantBits ushr 16
            val currMsb = ids[i].mostSignificantBits ushr 16
            assertThat(currMsb).isGreaterThanOrEqualTo(prevMsb)
        }
    }

    @Test
    fun `of는 문자열을 그대로 UUID로 파싱한다`() {
        val raw = UserId.generate().value.toString()

        assertThat(UserId.of(raw).value.toString()).isEqualTo(raw)
    }
}
