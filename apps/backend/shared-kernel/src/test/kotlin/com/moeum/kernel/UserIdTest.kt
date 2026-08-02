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
    fun `연속 생성한 UUID는 시간순으로 대체로 증가한다`() {
        val ids = (1..100).map { UserId.generate().value }

        // v7은 타임스탬프가 앞자리라 unsigned 비교 기준으로 비내림차순이어야 한다.
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
