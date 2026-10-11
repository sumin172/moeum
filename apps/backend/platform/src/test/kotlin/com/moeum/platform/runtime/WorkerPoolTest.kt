package com.moeum.platform.runtime

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkerPoolTest {
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
