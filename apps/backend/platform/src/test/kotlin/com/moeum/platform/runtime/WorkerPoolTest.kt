package com.moeum.platform.runtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkerPoolTest {
    @Test
    fun `배치 선점은 한 번만 진행하고 남은 슬롯에서는 AI 작업과 병렬로 진행한다`() {
        WorkerPool(3, "batch-test", Duration.ofSeconds(2)).use { pool ->
            val claiming = CountDownLatch(1)
            val allowClaim = CountDownLatch(1)
            val running = CountDownLatch(2)
            val release = CountDownLatch(1)
            try {
                assertThat(pool.submitBatch(3, { reserved ->
                    assertThat(reserved).isEqualTo(3)
                    claiming.countDown()
                    check(allowClaim.await(3, TimeUnit.SECONDS))
                    listOf(1, 2)
                }, { running.countDown(); release.await() })).isTrue()
                assertThat(claiming.await(2, TimeUnit.SECONDS)).isTrue()
                assertThat(pool.submitBatch(3, { error("중복 선점") }, { _: Int -> })).isFalse()
                allowClaim.countDown()
                assertThat(running.await(2, TimeUnit.SECONDS)).isTrue()
                val nextClaim = CountDownLatch(1)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (!pool.submitBatch(3, { reserved ->
                    assertThat(reserved).isEqualTo(1)
                    nextClaim.countDown()
                    emptyList<Int>()
                }, {}) && System.nanoTime() < deadline) Thread.yield()
                assertThat(nextClaim.await(2, TimeUnit.SECONDS)).isTrue()
            } finally { allowClaim.countDown(); release.countDown() }
        }
    }

    @Test
    fun `배치 선점 예외 후 예약 슬롯을 모두 반환한다`() {
        WorkerPool(2, "batch-failure", Duration.ofSeconds(1)).use { pool ->
            val failed = CountDownLatch(1)
            assertThat(pool.submitBatch<Int>(2, { failed.countDown(); error("claim failure") }, {})).isTrue()
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue()
            val recovered = CountDownLatch(2)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!pool.submitBatch(2, { reserved ->
                assertThat(reserved).isEqualTo(2)
                listOf(1, 2)
            }, { recovered.countDown() }) && System.nanoTime() < deadline) Thread.yield()
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun `슬롯 소진 시 선점 함수 자체를 실행하지 않고 다른 역할은 진행한다`() {
        WorkerPool(1, "journal-test", Duration.ofSeconds(1)).use { journal ->
            WorkerPool(1, "conversation-test", Duration.ofSeconds(1)).use { conversation ->
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val conversationDone = CountDownLatch(1)
                val rejectedClaim = AtomicBoolean(false)
                try {
                    assertThat(journal.submit { started.countDown(); release.await() }).isTrue()
                    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
                    assertThat(journal.submit { rejectedClaim.set(true) }).isFalse()
                    assertThat(conversation.submit { conversationDone.countDown() }).isTrue()
                    assertThat(conversationDone.await(2, TimeUnit.SECONDS)).isTrue()
                    assertThat(rejectedClaim.get()).isFalse()
                } finally { release.countDown() }
            }
        }
    }

    @Test
    fun `실패 후 슬롯을 반환하고 종료된 풀은 새 작업을 받지 않는다`() {
        val pool = WorkerPool(1, "failure-test", Duration.ofSeconds(1))
        try {
            val failed = CountDownLatch(1)
            assertThat(pool.submit { failed.countDown(); error("test failure") }).isTrue()
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue()
            val recovered = CountDownLatch(1)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!pool.submit { recovered.countDown() } && System.nanoTime() < deadline) Thread.yield()
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue()
        } finally { pool.close() }
        assertThat(pool.submit { error("종료 후 실행되면 안 됨") }).isFalse()
        pool.close()
    }
}
