package com.moeum.integration

import com.moeum.MoeumApplication
import com.moeum.conversation.application.command.ResponseJobExecutor
import com.moeum.conversation.infrastructure.ai.LlmConversationResponder
import com.moeum.conversation.infrastructure.scheduling.ResponseJobScheduler
import com.moeum.conversation.infrastructure.scheduling.TerminalResponseJobCleaner
import com.moeum.conversation.interfaces.ConversationController
import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.infrastructure.config.GoogleClientProperties
import com.moeum.identity.infrastructure.security.RefreshTokenCodec
import com.moeum.identity.interfaces.AuthController
import com.moeum.journal.application.command.GenerationExecutor
import com.moeum.journal.application.command.RecordDeliveryWorker
import com.moeum.journal.domain.GenerationJobRepository
import com.moeum.journal.infrastructure.ai.LlmJournalGenerator
import com.moeum.journal.infrastructure.scheduling.GenerationScheduler
import com.moeum.journal.infrastructure.scheduling.TerminalGenerationJobCleaner
import com.moeum.journal.interfaces.JournalController
import com.moeum.kernel.RecordDayId
import com.moeum.kernel.UserId
import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmMessage
import com.moeum.platform.llm.LlmProperties
import com.moeum.platform.llm.LlmRequest
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.llm.provider.LlmProviderResult
import com.moeum.platform.llm.provider.claude.ClaudeProperties
import com.moeum.platform.llm.provider.cloudflare.CloudflareProperties
import com.moeum.platform.llm.provider.gemini.GeminiProperties
import com.moeum.platform.observability.LlmTelemetry
import com.moeum.platform.runtime.WorkerPool
import com.moeum.platform.security.config.JwtProperties
import com.moeum.platform.security.jwt.JwtProvider
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansWithAnnotation
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.scheduling.config.TaskManagementConfigUtils
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.support.StandardServletEnvironment
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 별도 ApplicationContext/DB pool/LLM limiter로 실제 배포 역할 구성을 검증한다.
 * local 프로필·OS 환경변수·시스템 속성을 제외하고 역할 프로필과 명시한 설정만으로 기동한다.
 */
class WorkerRoleIsolationIntegrationTest {
    @Test
    fun `일기 워커 지연과 종료 중에도 API와 대화가 진행되고 재기동 후 만료 리스를 회수한다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).use { postgres ->
            postgres.start()
            val provider = BlockingJournalProvider()
            val contexts = mutableListOf<ConfigurableApplicationContext>()
            fun start(role: String): ConfigurableApplicationContext = launch(
                postgres, role, provider.takeIf { role != "api" },
                requiredSettings.getValue(role) + listOf(
                    "--moeum.llm.routes.conversation-response.provider=isolation",
                    "--moeum.llm.routes.journal-generation.provider=isolation",
                    "--moeum.llm.max-concurrent-calls.isolation=${if (role == "journal-worker") 2 else 8}",
                    "--moeum.conversation.response-job.poll-interval=PT0.1S",
                    "--moeum.journal.generation-job.poll-interval=PT0.1S",
                    "--moeum.journal.generation-job.backoff=1h",
                    "--moeum.runtime.shutdown-timeout=0s",
                ),
            ).also { contexts += it }

            try {
                val api = start("api")
                val jdbc = api.getBean<JdbcTemplate>()
                val userId = UserId.generate()
                val jwt = api.getBean<JwtProvider>().issue(userId).token
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
                // 응답 작업은 기록당 한 행을 재사용하므로 완료된 답변은 ASSISTANT 메시지로 센다.
                fun completedResponses(): Int = jdbc.queryForObject<Int>(
                    "SELECT count(*) FROM conversation.messages WHERE user_id = ? AND role = 'ASSISTANT'",
                    userId.value,
                )!!

                assertRole(api, "api")
                assertThat(api.getBean<HikariDataSource>().maximumPoolSize).isEqualTo(10)
                assertThat(send().statusCode()).isEqualTo(200)
                assertThat(provider.conversationCalls.get()).isZero()
                assertThat(completedResponses()).isZero()

                val conversation = start("conversation-worker")
                assertRole(conversation, "conversation-worker")
                assertThat(conversation.getBean<HikariDataSource>().maximumPoolSize).isEqualTo(4)
                await { completedResponses() == 1 }

                val recordId = jdbc.queryForObject<java.util.UUID>("SELECT id FROM conversation.record_days WHERE user_id = ?",
                    userId.value)!!
                jdbc.update("UPDATE conversation.record_days SET closed_at = now(),close_at = now() WHERE id = ?",recordId)
                val job = api.getBean<GenerationJobRepository>().save(
                    com.moeum.journal.domain.model.TestGenerationJobs.pending(userId, RecordDayId(recordId), Instant.now().minusSeconds(1), Instant.now()),
                )
                val journal = start("journal-worker")
                assertRole(journal, "journal-worker")
                assertThat(journal.getBean<HikariDataSource>().maximumPoolSize).isEqualTo(2)
                assertThat(provider.journalStarted.await(5, TimeUnit.SECONDS)).isTrue()

                assertThat(send().statusCode()).isEqualTo(200)
                await { completedResponses() == 2 }
                assertThat(jdbc.queryForObject<String>(
                    "SELECT status FROM journal.generation_jobs WHERE id = ?", job.id.value,
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
                    jdbc.queryForObject<String>("SELECT status FROM journal.generation_jobs WHERE id = ?", job.id.value) == "COMPLETED"
                }
                assertThat(provider.journalInputs).hasSize(2)
                assertThat(provider.journalInputs[1]).isEqualTo(provider.journalInputs[0])
                assertThat(jdbc.queryForObject<Int>(
                    "SELECT attempt_count FROM journal.generation_jobs WHERE id = ?", job.id.value,
                )).isEqualTo(2)
                assertThat(jdbc.queryForObject<Int>(
                    "SELECT count(*) FROM journal.journals WHERE user_id = ?", userId.value,
                )).isEqualTo(1)
            } finally {
                provider.releaseJournal.countDown()
                contexts.asReversed().forEach { it.close() }
            }
        }
    }

    @Test
    fun `각 역할은 다른 역할의 설정 없이 기동하고 그 역할의 빈과 설정 검증을 활성화하지 않는다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).use { postgres ->
            postgres.start()
            // 아래 무효 값이 해당 역할에서는 실제로 기동을 거부함을 먼저 확인해 미등록 검증이 공허하지 않게 한다.
            assertThatThrownBy { launch(postgres, "api", null, requiredSettings.getValue("api") - JWT_SECRET).close() }
                .hasStackTraceContaining("moeum.jwt")
            assertThatThrownBy { launch(postgres, "journal-worker", null, requiredSettings.getValue("journal-worker") + invalidLlmSettings).close() }
                .hasStackTraceContaining("LLM 동시 호출 상한은 양수여야 합니다")
            assertThatThrownBy { launch(postgres, "api", null, requiredSettings.getValue("api") - SIGNING_SECRET + invalidApiSettings).close() }
                .hasStackTraceContaining("refresh token signing secret")

            // API: LLM 키 없이, 무효한 LLM 설정이 있어도 기동한다.
            launch(postgres, "api", null, requiredSettings.getValue("api") + invalidLlmSettings).use { assertRole(it, "api") }
            // 워커: JWT·Google 설정 없이, 무효한 refresh token 서명 키가 있어도 기동한다.
            listOf("conversation-worker", "journal-worker").forEach { role ->
                launch(postgres, role, null, requiredSettings.getValue(role) + invalidApiSettings).use { assertRole(it, role) }
            }
        }
    }

    private fun launch(
        postgres: PostgreSQLContainer,
        role: String,
        provider: LlmProvider?,
        settings: List<String>,
    ): ConfigurableApplicationContext {
        val api = role == "api"
        val environment = (if (api) StandardServletEnvironment() else StandardEnvironment()).apply {
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        }
        return SpringApplicationBuilder(MoeumApplication::class.java)
            .environment(environment)
            .profiles(role)
            .web(if (api) WebApplicationType.SERVLET else WebApplicationType.NONE)
            .initializers({ context -> provider?.let { context.beanFactory.registerSingleton("isolationProvider", it) } })
            .run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--server.port=0",
                "--moeum.journal.delivery.initial-delay=PT1H",
                "--logging.level.com.moeum=INFO",
                *settings.toTypedArray(),
            )
    }

    /** 역할이 기동에 필요로 하는 설정만 명시한다. */
    private val requiredSettings = mapOf(
        "api" to listOf(
            JWT_SECRET,
            SIGNING_SECRET,
            "--moeum.google.client-ids=isolation-client",
        ),
        "conversation-worker" to LLM_KEYS,
        "journal-worker" to LLM_KEYS,
    )
    private val invalidLlmSettings = listOf("--moeum.llm.max-concurrent-calls.gemini=0")
    private val invalidApiSettings = listOf("--moeum.auth.session.signing-secret=short")

    private val apiBeans = listOf(
        AuthController::class.java, ConversationController::class.java, JournalController::class.java,
        SecurityFilterChain::class.java, JwtProvider::class.java, JwtProperties::class.java,
        RefreshTokenCodec::class.java, GoogleIdTokenVerifierPort::class.java, GoogleClientProperties::class.java,
    )
    private val workerBeans = listOf(
        LlmClient::class.java, LlmProvider::class.java, LlmProperties::class.java, LlmTelemetry::class.java,
        GeminiProperties::class.java, ClaudeProperties::class.java, CloudflareProperties::class.java,
        WorkerPool::class.java,
    )
    private val conversationWorkerBeans = listOf(
        ResponseJobScheduler::class.java, ResponseJobExecutor::class.java,
        LlmConversationResponder::class.java, TerminalResponseJobCleaner::class.java,
    )
    private val journalWorkerBeans = listOf(
        GenerationScheduler::class.java, GenerationExecutor::class.java, RecordDeliveryWorker::class.java,
        LlmJournalGenerator::class.java, TerminalGenerationJobCleaner::class.java,
    )

    private fun assertRole(context: ConfigurableApplicationContext, role: String) {
        val expected = when (role) {
            "api" -> apiBeans
            "conversation-worker" -> workerBeans + conversationWorkerBeans
            else -> workerBeans + journalWorkerBeans
        }
        (apiBeans + workerBeans + conversationWorkerBeans + journalWorkerBeans).forEach { type ->
            val names = context.getBeanNamesForType(type)
            if (type in expected) assertThat(names).describedAs("$role: ${type.simpleName}").isNotEmpty()
            else assertThat(names).describedAs("$role: ${type.simpleName}").isEmpty()
        }
        assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isEqualTo(role != "api")
        if (role != "api") assertThat(context.getBeansWithAnnotation<RestController>()).isEmpty()
    }

    private companion object {
        const val JWT_SECRET = "--moeum.jwt.secret=isolation-jwt-secret-must-be-32-bytes-min"
        const val SIGNING_SECRET = "--moeum.auth.session.signing-secret=isolation-refresh-signing-secret-32-bytes"
        val LLM_KEYS = listOf("--moeum.gemini.api-key=isolation", "--moeum.claude.api-key=isolation")
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
