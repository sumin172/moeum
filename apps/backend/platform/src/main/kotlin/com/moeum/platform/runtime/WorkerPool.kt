package com.moeum.platform.runtime

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 대기열 없이 슬롯을 확보한다. DB 선점은 반드시 task 안에서 수행한다. */
class WorkerPool(
    val concurrency: Int,
    private val name: String,
    private val shutdownTimeout: Duration,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(WorkerPool::class.java)
    private val slots = Semaphore(concurrency)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("$name-", 0).factory())

    init { require(concurrency > 0) }

    fun submit(task: () -> Unit): Boolean {
        if (closed.get() || !slots.tryAcquire()) return false
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log.error("Worker 실행 실패: role={}", name, e)
                } finally {
                    slots.release()
                }
            }
        } catch (_: RejectedExecutionException) {
            slots.release()
            return false
        }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
