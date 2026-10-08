package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.journal.domain.model.GenerationJobId
import com.moeum.kernel.UserId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 공통 작업 선점 SQL(JobClaimSql)과 version 기반 fencing을 실제 Postgres로 검증한다.
 *
 * 실제 시각으로 도는 스케줄러와 섞이지 않도록, 작업은 먼 미래가 "하루 끝"이 되게 만들고
 * 선점할 때만 그 미래 시각을 now로 넘긴다.
 */
class JobClaimIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var generationJobRepository: GenerationJobRepository

    private val farFuture = Instant.now().plus(Duration.ofDays(365))

    private fun createJobs(count: Int): Set<GenerationJobId> =
        (1..count).map {
            generationJobRepository.save(
                GenerationJob.pending(UserId.generate(), LocalDate.of(2027, 1, 1), dayEnd = farFuture, now = Instant.now()),
            ).id
        }.toSet()

    @Test
    fun `여러 워커가 동시에 선점해도 같은 작업을 두 번 잡지 않는다`() {
        val created = createJobs(20)
        val claimAt = farFuture.plusSeconds(1)
        val workers = Executors.newFixedThreadPool(8)

        val claimedPerWorker = workers.invokeAll(
            (1..8).map {
                Callable {
                    generateSequence { generationJobRepository.claimNext(claimAt, claimAt.plus(Duration.ofMinutes(5))) }
                        .map { it.id }
                        .toList()
                }
            },
        ).map { it.get() }
        workers.shutdown()

        val allClaimed = claimedPerWorker.flatten()
        assertThat(allClaimed).doesNotHaveDuplicates()
        assertThat(allClaimed).containsAll(created)
    }

    @Test
    fun `리스가 만료된 작업은 다시 선점되고, 리스를 잃은 워커의 늦은 저장은 거부된다`() {
        createJobs(1)
        val firstAt = farFuture.plus(Duration.ofDays(1))
        val first = generateSequence { generationJobRepository.claimNext(firstAt, firstAt.plus(Duration.ofMinutes(5))) }
            .first { it.diaryDate == LocalDate.of(2027, 1, 1) && it.state.attemptCount == 1 }

        // 리스(5분)가 지난 뒤 다른 워커가 같은 작업을 다시 잡는다
        val secondAt = firstAt.plus(Duration.ofMinutes(6))
        val second = generateSequence { generationJobRepository.claimNext(secondAt, secondAt.plus(Duration.ofMinutes(5))) }
            .first { it.id == first.id }
        assertThat(second.state.attemptCount).isEqualTo(2)
        assertThat(second.state.version).isGreaterThan(first.state.version)

        assertThatThrownBy { generationJobRepository.save(first.failedPermanently("LATE", secondAt)) }
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        // 현재 리스를 가진 워커의 저장은 성공한다
        generationJobRepository.save(second.failedPermanently("DONE", secondAt))
    }
}
