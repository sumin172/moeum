package com.moeum.integration

import com.moeum.integration.support.AbstractIntegrationTest
import com.moeum.journal.application.command.MarkGenerationFailedService
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJobId
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.job.JobStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.OptimisticLockingFailureException
import java.time.Duration
import java.time.Instant
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

    @Autowired
    lateinit var markGenerationFailedService: MarkGenerationFailedService

    private val farFuture = Instant.now().plus(Duration.ofDays(365))

    private fun createJobs(count: Int): Set<GenerationJobId> =
        (1..count).map {
            generationJobRepository.save(
                com.moeum.journal.domain.model.TestGenerationJobs.pending(UserId.generate(), RecordDayId.generate(), dayEnd = farFuture, now = Instant.now()),
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
                    generateSequence { generationJobRepository.claimNext(claimAt) }
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
    fun `리스가 만료된 작업은 다시 선점되지 않고 FAILED로 정리되며, 재요청 뒤에도 이전 시도의 늦은 저장은 거부된다`() {
        createJobs(1)
        val firstAt = farFuture.plus(Duration.ofDays(1))
        val first = generateSequence { generationJobRepository.claimNext(firstAt) }
            .first { it.state.attemptCount == 1 }

        // 리스(2분)가 지나도 선점 SQL은 만료 작업을 다시 잡지 않는다
        val expiredAt = firstAt.plus(Duration.ofMinutes(3))
        val claimedAfterExpiry = generateSequence { generationJobRepository.claimNext(expiredAt) }
            .map { it.id }.toList()
        assertThat(claimedAfterExpiry).doesNotContain(first.id)

        generateSequence { markGenerationFailedService.failExpired(expiredAt, 100) }.flatten().first { it.id == first.id }
        // version은 커밋 시 오르므로 저장된 상태를 다시 읽는다
        val failed = generationJobRepository.findByUserIdAndRecordDayId(first.userId, first.recordDayId)!!
        assertThat(failed.state.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.state.attemptCount).isEqualTo(1)
        assertThat(failed.state.version).isGreaterThan(first.state.version)
        assertThatThrownBy { generationJobRepository.save(first.completed(expiredAt)) }
            .isInstanceOf(OptimisticLockingFailureException::class.java)

        // 사용자 재요청 뒤 새 시도만 저장할 수 있다
        val retryAt = expiredAt.plusSeconds(1)
        generationJobRepository.save(failed.restarted(retryAt))
        val second = generateSequence { generationJobRepository.claimNext(retryAt) }
            .first { it.id == first.id }
        assertThat(second.state.attemptCount).isEqualTo(2)
        assertThatThrownBy { generationJobRepository.save(first.completed(retryAt)) }
            .isInstanceOf(OptimisticLockingFailureException::class.java)
        generationJobRepository.save(second.completed(retryAt))
    }
}
