package com.moeum.platform.llm.provider.cloudflare

import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
@Configuration
class CloudflareClientConfig {
    @Bean
    fun cloudflareRestClient(properties: CloudflareProperties): RestClient {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectionTimeoutMs.toLong()))
            .build()
        val factory = JdkClientHttpRequestFactory(client).apply {
            setReadTimeout(Duration.ofMillis(properties.readTimeoutMs.toLong()))
        }
        return RestClient.builder().baseUrl(properties.baseUrl).requestFactory(factory).build()
    }
}
