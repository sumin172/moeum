package com.moeum.integration.support

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.model.GoogleProfile
import com.moeum.identity.interfaces.dto.GoogleLoginRequest
import com.moeum.identity.interfaces.dto.AuthTokenResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.resttestclient.postForEntity
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * 실제 Postgres(Testcontainers) + Flyway + JPA + Security 필터 체인을 전부 태우는 통합 테스트 베이스.
 * Google 실서버 호출만 [FakeGoogleVerifierConfig]로 대체한다 — 그 경계 밖은 전부 프로덕션 그대로 동작한다.
 */
@ActiveProfiles("local")
@AutoConfigureTestRestTemplate
@Import(
    AbstractIntegrationTest.FakeGoogleVerifierConfig::class,
    AbstractIntegrationTest.FakeConversationResponderConfig::class,
    AbstractIntegrationTest.FakeLlmProviderConfig::class,
    AbstractIntegrationTest.EventRecorderConfig::class,
)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // 재시도 흐름을 몇 초 안에 끝까지 검증할 수 있도록 응답 작업 backoff와 poll 주기를 줄인다
    properties = [
        "moeum.conversation.response-job.backoff=200ms",
        "moeum.conversation.response-job.poll-interval=PT0.3S",
        // 실제 provider 없이 LlmClient → 호출 원장 경로를 검증하기 위한 용도(FakeLlmProvider)
        "moeum.llm.routes.integration-test.provider=fake",
        "moeum.llm.routes.integration-test.model=fake-model",
        "moeum.llm.routes.integration-test.max-output-tokens=100",
        // 일기 생성도 실제 provider 대신 FakeLlmProvider로(생성 흐름을 끝까지 검증하기 위함)
        "moeum.llm.routes.journal-generation.provider=fake",
    ],
)
abstract class AbstractIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var conversationResponder: ControllableConversationResponder

    @BeforeEach
    fun resetResponder() = conversationResponder.reset()

    // 새 사용자로 로그인한다(FakeGoogleVerifier는 idToken마다 다른 구글 계정으로 본다)
    protected fun login(idToken: String = "dummy-${UUID.randomUUID()}", deviceId: UUID = UUID.randomUUID()): AuthTokenResponse {
        val response = restTemplate.postForEntity<AuthTokenResponse>(
            "/api/auth/google",
            GoogleLoginRequest(idToken = idToken, deviceId = deviceId),
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return response.body!!
    }

    protected fun issueJwt(): String = login().accessToken

    protected fun authHeaders(jwt: String): HttpHeaders =
        HttpHeaders().apply {
            setBearerAuth(jwt)
            contentType = MediaType.APPLICATION_JSON
        }

    companion object {
        // Testcontainers Singleton Container 패턴.
        //
        // @Container로 JUnit lifecycle에 맡기면 static container도 테스트 클래스 종료 시 stop된다.
        // 하지만 Spring TestContext는 ApplicationContext를 테스트 클래스 사이에서 cache/reuse할 수 있어,
        // cached context가 이미 종료된 container의 connection 정보를 계속 참조하는 문제가 발생할 수 있다.
        //
        // 따라서 JUnit lifecycle에서 분리해 한 번만 직접 start하고 테스트 JVM 동안 유지한다.
        // JVM 종료 시 container 정리는 Testcontainers Ryuk이 담당한다.
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply { start() }
    }

    @TestConfiguration
    class FakeGoogleVerifierConfig {
        @Bean
        @Primary
        fun fakeGoogleIdTokenVerifierPort(): GoogleIdTokenVerifierPort =
            object : GoogleIdTokenVerifierPort {
                override fun verify(idToken: String): GoogleProfile =
                    GoogleProfile(googleId = "test-google-$idToken", email = "test-$idToken@example.com")
            }
    }

    @TestConfiguration
    class FakeConversationResponderConfig {
        @Bean
        @Primary
        fun fakeConversationResponder(): ControllableConversationResponder = ControllableConversationResponder()
    }

    @TestConfiguration
    class FakeLlmProviderConfig {
        @Bean
        fun fakeLlmProvider(): FakeLlmProvider = FakeLlmProvider()
    }

    @TestConfiguration
    class EventRecorderConfig {
        @Bean
        fun integrationEventRecorder(): IntegrationEventRecorder = IntegrationEventRecorder()
    }
}
