package com.moeum.platform.runtime

import com.moeum.platform.observability.SafeFailure
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 대기열 없이 빈 슬롯만큼 예약하고, 워커 스레드 안에서 한 트랜잭션으로 선점한 작업을 슬롯마다 실행한다. */
class WorkerPool(
    concurrency: Int,
    private val name: String,
    private val shutdownTimeout: Duration,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(WorkerPool::class.java)
    private val slots = Semaphore(concurrency)
    private val closed = AtomicBoolean(false)
    private val claiming = AtomicBoolean(false)
    private val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("$name-", 0).factory())

    init { require(concurrency > 0) }

    /** 선점 전 실행 슬롯을 예약하고, 선점 트랜잭션은 풀당 하나씩만 진행한다. */
    fun <T> submitBatch(claim: (Int) -> List<T>, task: (T) -> Unit): Boolean {
        if (closed.get() || !claiming.compareAndSet(false, true)) return false
        val reserved = minOf(MAX_CLAIM_BATCH, slots.availablePermits())
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
                        if (rejected > 0) log.warn("종료 중 선점 작업 인계 실패: role={}, count={}; 리스 만료 후 FAILED로 정리", name, rejected)
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

    companion object {
        // 한 선점 트랜잭션이 잠그는 작업 수 상한. 슬롯이 많아도 선점 트랜잭션을 짧게 유지한다.
        const val MAX_CLAIM_BATCH = 32
    }
}
