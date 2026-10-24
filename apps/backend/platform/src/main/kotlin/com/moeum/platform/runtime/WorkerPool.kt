package com.moeum.platform.runtime

import com.moeum.platform.observability.SafeFailure
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
    private val claiming = AtomicBoolean(false)
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("$name-", 0).factory())

    init { require(concurrency > 0) }

    fun submit(task: () -> Unit): Boolean {
        return !(closed.get() || !slots.tryAcquire()) && executeReserved(task)
    }

    /** 선점 전 실행 슬롯을 예약하고, DB 트랜잭션은 풀당 하나씩만 진행한다. */
    fun <T> submitBatch(limit: Int, claim: (Int) -> List<T>, task: (T) -> Unit): Boolean {
        require(limit > 0)
        if (closed.get() || !claiming.compareAndSet(false, true)) return false
        val reserved = minOf(limit, slots.availablePermits())
        if (reserved == 0 || !slots.tryAcquire(reserved)) {
            claiming.set(false)
            return false
        }
        try {
            executor.execute {
                var owned = reserved
                try {
                    if (!closed.get()) {
                        val jobs = claim(reserved)
                        check(jobs.size <= reserved)
                        slots.release(reserved - jobs.size)
                        owned = jobs.size
                        var rejected = 0
                        for (job in jobs) {
                            owned--
                            if (!executeReserved { task(job) }) rejected++
                        }
                        if (rejected > 0) log.warn("종료 중 선점 작업 인계 실패: role={}, count={}; lease 만료 후 회수", name, rejected)
                    }
                } catch (e: Exception) {
                    log.error("Worker 선점 실패: role={}, code={}", name, SafeFailure.code(e))
                } finally {
                    slots.release(owned)
                    claiming.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            slots.release(reserved)
            claiming.set(false)
            return false
        }
        return true
    }

    private fun executeReserved(task: () -> Unit): Boolean {
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    log.error("Worker 실행 실패: role={}, code={}", name, SafeFailure.code(e))
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
