package com.moeum.integration

import com.moeum.MoeumApplication
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.domain.model.GenerationJob
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import com.moeum.platform.security.jwt.JwtProvider
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.config.TaskManagementConfigUtils
import org.springframework.web.bind.annotation.RestController
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 별도 ApplicationContext/DB pool/LLM limiter로 실제 배포 역할 구성을 검증한다. */
class WorkerRoleIsolationIntegrationTest {
    @Test
    fun `일기 워커 지연과 종료 중에도 API와 대화가 진행되고 재기동 후 만료 리스를 회수한다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).use { postgres ->
            postgres.start()
            val provider = BlockingJournalProvider()
            val contexts = mutableListOf<ConfigurableApplicationContext>()
            fun start(role: String): ConfigurableApplicationContext = SpringApplicationBuilder(MoeumApplication::class.java)
                .profiles("local", role)
                .web(if (role == "api") WebApplicationType.SERVLET else WebApplicationType.NONE)
                .initializers({ context -> context.beanFactory.registerSingleton("isolationProvider", provider) })
                .run(
                    "--spring.datasource.url=${postgres.jdbcUrl}",
                    "--spring.datasource.username=${postgres.username}",
                    "--spring.datasource.password=${postgres.password}",
                    "--server.port=0",
                    "--moeum.llm.routes.conversation-response.provider=isolation",
                    "--moeum.llm.routes.journal-generation.provider=isolation",
                    "--moeum.llm.max-concurrent-calls.isolation=${if (role == "journal-worker") 2 else 8}",
                    "--moeum.conversation.response-job.poll-interval=PT0.1S",
                    "--moeum.journal.generation-job.poll-interval=PT0.1S",
                    "--moeum.journal.generation-job.backoff=1h",
                    "--moeum.journal.delivery.initial-delay=PT1H",
                    "--moeum.runtime.shutdown-timeout=0s",
                    "--logging.level.com.moeum=INFO",
                ).also { contexts += it }

            try {
                val api = start("api")
                val jdbc = api.getBean(JdbcTemplate::class.java)
                val userId = UserId.generate()
                val jwt = api.getBean(JwtProvider::class.java).issue(userId).token
                val port = api.environment.getRequiredProperty("local.server.port")
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                fun send(): HttpResponse<String> = client.send(
                    HttpRequest.newBuilder(URI("http://localhost:$port/api/conversations/messages"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Bearer $jwt")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                            """{"clientMessageId":"${java.util.UUID.randomUUID()}","content":"오늘 산책했다","occurredAt":"${Instant.now()}","timezone":"Asia/Seoul"}""",
                        )).build(), HttpResponse.BodyHandlers.ofString(),
                )
                fun completedResponses(): Int = jdbc.queryForObject(
                    "SELECT count(*) FROM conversation.response_jobs WHERE user_id = ? AND status = 'COMPLETED'",
                    Int::class.java, userId.value,
                )!!

                assertThat(api.getBeansOfType(LlmClient::class.java)).isEmpty()
                assertThat(api.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse()
                assertThat(api.getBean(HikariDataSource::class.java).maximumPoolSize).isEqualTo(10)
                assertThat(send().statusCode()).isEqualTo(200)
                assertThat(provider.conversationCalls.get()).isZero()
                assertThat(completedResponses()).isZero()

                val conversation = start("conversation-worker")
                assertWorker(conversation, "responseJobExecutor", "generationExecutor", 4)
                await { completedResponses() == 1 }

                val recordId = jdbc.queryForObject("SELECT id FROM conversation.record_days WHERE user_id = ?",java.util.UUID::class.java,userId.value)!!
                jdbc.update("UPDATE conversation.record_days SET closed_at = now(),close_at = now() WHERE id = ?",recordId)
                jdbc.update("UPDATE conversation.user_record_states SET active_record_day_id = NULL WHERE user_id = ?",userId.value)
                val job = api.getBean(GenerationJobRepository::class.java).save(
                    GenerationJob.pending(userId, RecordDayId(recordId), LocalDate.now(), Instant.now().minusSeconds(1), Instant.now()),
                )
                val journal = start("journal-worker")
                assertWorker(journal, "generationExecutor", "responseJobExecutor", 2)
                assertThat(provider.journalStarted.await(5, TimeUnit.SECONDS)).isTrue()

                assertThat(send().statusCode()).isEqualTo(200)
                await { completedResponses() == 2 }
                assertThat(jdbc.queryForObject(
                    "SELECT status FROM journal.generation_jobs WHERE id = ?", String::class.java, job.id.value,
                )).isEqualTo("PROCESSING")

                journal.close()
                assertThat(send().statusCode()).isEqualTo(200)
                await { completedResponses() == 3 }

                // 프로세스가 강제 종료되어 실패 기록을 남기지 못한 상태를 재현한다.
                // DB 리스 시각만 당겨 5분의 실제 대기 없이 같은 회수 조건을 검증한다.
                jdbc.update(
                    "UPDATE journal.generation_jobs SET status = 'PROCESSING', lease_expires_at = now() - interval '1 second' WHERE id = ?",
                    job.id.value,
                )
                provider.blockJournal.set(false)
                start("journal-worker")
                await {
                    jdbc.queryForObject("SELECT status FROM journal.generation_jobs WHERE id = ?", String::class.java, job.id.value) == "COMPLETED"
                }
                assertThat(provider.journalInputs).hasSize(2)
                assertThat(provider.journalInputs[1]).isEqualTo(provider.journalInputs[0])
                assertThat(jdbc.queryForObject(
                    "SELECT attempt_count FROM journal.generation_jobs WHERE id = ?", Int::class.java, job.id.value,
                )).isEqualTo(2)
                assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM journal.journals WHERE user_id = ?", Int::class.java, userId.value,
                )).isEqualTo(1)
            } finally {
                provider.releaseJournal.countDown()
                contexts.asReversed().forEach { it.close() }
            }
        }
    }

    private fun assertWorker(context: ConfigurableApplicationContext, executor: String, excluded: String, pool: Int) {
        assertThat(context.getBeansWithAnnotation(RestController::class.java)).isEmpty()
        assertThat(context.getBeansOfType(JwtProvider::class.java)).isEmpty()
        assertThat(context.containsBean(executor)).isTrue()
        assertThat(context.containsBean(excluded)).isFalse()
        assertThat(context.getBean(HikariDataSource::class.java).maximumPoolSize).isEqualTo(pool)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertThat(condition()).isTrue()
    }

    private class BlockingJournalProvider : LlmProvider {
        override val name = "isolation"
        override val vendor = "test"
        val journalInputs = java.util.concurrent.CopyOnWriteArrayList<List<LlmMessage>>()
        val blockJournal = AtomicBoolean(true)
        val journalStarted = CountDownLatch(1)
        val releaseJournal = CountDownLatch(1)
        val conversationCalls = AtomicInteger()

        override fun generate(route: LlmRoute, request: LlmRequest): LlmProviderResult {
            val text = if (request.purpose == "journal-generation") {
                journalInputs.add(request.messages.toList())
                journalStarted.countDown()
                if (blockJournal.get()) releaseJournal.await()
                """{"title":"산책","body":"오늘 산책했다"}"""
            } else {
                conversationCalls.incrementAndGet()
                "산책은 어땠어?"
            }
            return LlmProviderResult(text, route.model, 7, 3)
        }
    }
}
