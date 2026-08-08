package com.moeum.integration.support

import com.moeum.identity.domain.GoogleIdTokenVerifierPort
import com.moeum.identity.domain.model.GoogleProfile
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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 실제 Postgres(Testcontainers) + Flyway + JPA + Security 필터 체인을 전부 태우는 통합 테스트 베이스.
 * Google 실서버 호출만 [FakeGoogleVerifierConfig]로 대체한다 — 그 경계 밖은 전부 프로덕션 그대로 동작한다.
 */
@Testcontainers
@ActiveProfiles("local")
@AutoConfigureTestRestTemplate
@Import(AbstractIntegrationTest.FakeGoogleVerifierConfig::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
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
}
