package com.moeum.integration.support

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.model.GoogleProfile
import com.moeum.platform.llm.conversation.ConversationRequest
import com.moeum.platform.llm.conversation.ConversationResponder
import com.moeum.platform.llm.conversation.ConversationResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
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
@Import(AbstractIntegrationTest.FakeGoogleVerifierConfig::class, AbstractIntegrationTest.FakeConversationResponderConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

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
        fun fakeConversationResponder(): ConversationResponder =
            object : ConversationResponder {
                override fun respond(request: ConversationRequest): ConversationResponse =
                    ConversationResponse(
                        generationId = UUID.randomUUID(),
                        content = "테스트 응답입니다.",
                        model = "fake-model",
                        provider = "fake",
                        promptVersion = "test",
                        inputTokens = 1,
                        outputTokens = 1,
                    )
            }
    }
}
