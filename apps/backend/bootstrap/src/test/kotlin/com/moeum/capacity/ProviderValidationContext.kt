package com.moeum.capacity

import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.LlmProperties
import com.moeum.platform.llm.LlmRoute
import com.moeum.platform.llm.RoutingLlmClient
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.observability.LlmCallEvent
import com.moeum.platform.observability.LlmTelemetry
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansWithAnnotation
import org.springframework.boot.Banner
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.web.client.DefaultResponseErrorHandler
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper

/**
 * 운영과 같은 Boot 설정 로딩(local → 워커 프로필 순서)·공급자 등록·용도별 라우팅으로 실제 공급자를 부른다.
 * DB·JPA·스케줄러와 서킷브레이커·관측 버퍼는 실행하지 않는다. 검증 범위는 실제 공급자 호출과 생성 계약이며 운영 장애 격리는 포함하지 않는다.
 */
internal class ProviderValidationContext private constructor(
    private val context: ConfigurableApplicationContext,
    private val observer: CallObserver,
) : AutoCloseable {
    val client: LlmClient = context.getBean()

    fun route(purpose: String): LlmRoute = context.getBean<LlmProperties>().routes.getValue(purpose)

    // 보고서에 남길 연결 조건. 인증·계정 식별 값은 제외한다.
    fun providerSettings(provider: String): Map<String, Any?> {
        val properties = context.getBeansWithAnnotation<ConfigurationProperties>().values.firstOrNull {
            AnnotationUtils.findAnnotation(it.javaClass, ConfigurationProperties::class.java)?.prefix == "moeum.$provider"
        } ?: return emptyMap()
        @Suppress("UNCHECKED_CAST")
        val values = MAPPER.convertValue(properties, Map::class.java) as Map<String, Any?>
        return values.filterKeys { !SENSITIVE.containsMatchIn(it) }
    }

    // 라우팅 밖의 업무 설정(대화 한도 등)도 같은 Boot 환경에서 읽는다.
    fun <T : Any> bind(prefix: String, type: Class<T>): T = Binder.get(context.environment).bindOrCreate(prefix, type)

    fun <T> observe(block: () -> T): Observed<T> = observer.observe(block)

    override fun close() = context.close()

    companion object {
        private val MAPPER = JsonMapper.builder().build()
        private val SENSITIVE = Regex("(?i)key|token|secret|account")

        // overrides는 명령행 인자로 넘겨 운영 배포의 외부 설정처럼 가장 높은 우선순위를 갖는다.
        // includeLocal=false는 로컬 키 없이 실행 기반 자체를 시험할 때만 쓴다.
        fun start(workerProfile: String, overrides: Map<String, String>, includeLocal: Boolean = true): ProviderValidationContext {
            val observer = CallObserver()
            val profiles = if (includeLocal) arrayOf("local", workerProfile) else arrayOf(workerProfile)
            val context = SpringApplicationBuilder(ProviderValidationConfiguration::class.java)
                .profiles(*profiles)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .initializers({ ctx ->
                    ctx.beanFactory.registerSingleton("providerValidationTelemetry", LlmTelemetry(observer::record))
                    ctx.beanFactory.addBeanPostProcessor(ProviderFailureCaptureProcessor(observer::capture))
                })
                .run(*overrides.map { (key, value) -> "--$key=$value" }.toTypedArray())
            return ProviderValidationContext(context, observer)
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = [LlmProvider::class])
@ConfigurationPropertiesScan(basePackageClasses = [LlmProperties::class])
@Import(RoutingLlmClient::class)
internal class ProviderValidationConfiguration

internal data class Observed<T>(val result: Result<T>, val events: List<LlmCallEvent>, val httpFailure: Map<String, Any?>?)

// 동시 호출 검증도 호출 스레드 단위로 관측·오류 요약을 분리한다. 공급자 호출과 관측 기록은 호출 스레드에서 동기로 일어난다.
internal class CallObserver {
    private val events = ThreadLocal<MutableList<LlmCallEvent>?>()
    private val failure = ThreadLocal<Map<String, Any?>?>()

    fun record(event: LlmCallEvent) { events.get()?.add(event) }
    fun capture(summary: Map<String, Any?>) { failure.set(summary) }

    fun <T> observe(block: () -> T): Observed<T> {
        events.set(mutableListOf())
        failure.remove()
        try {
            val result = runCatching(block)
            return Observed(result, events.get()!!.toList(), failure.get())
        } finally {
            events.remove()
            failure.remove()
        }
    }
}

private class ProviderFailureCaptureProcessor(private val sink: (Map<String, Any?>) -> Unit) : BeanPostProcessor {
    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
        if (bean is RestClient) withProviderFailureCapture(bean, sink) else bean
}

// 기본 오류 처리에 그대로 위임해 같은 예외를 다시 던지고, 그 사이 본문 없는 분류만 기록한다.
internal fun withProviderFailureCapture(client: RestClient, sink: (Map<String, Any?>) -> Unit): RestClient {
    val mapper = JsonMapper.builder().build()
    return client.mutate().defaultStatusHandler({ it.isError }) { request, response ->
        try {
            DefaultResponseErrorHandler().handleError(request.uri, request.method, response)
        } catch (error: RestClientResponseException) {
            sink(summarizeProviderFailure(error, mapper))
            throw error
        }
    }.build()
}

/** 오류 본문은 내보내지 않고 프로토콜·공급자 오류 분류만 보존한다. */
internal fun summarizeProviderFailure(error: RestClientResponseException, mapper: JsonMapper): Map<String, Any?> {
    val root = runCatching { mapper.readTree(error.responseBodyAsByteArray) }.getOrNull()?.get("error")
    val message = root?.get("message")?.asString().orEmpty().lowercase()
    val diagnosis = when {
        root == null -> "NON_API_ERROR_BODY"
        "free tier" in message -> "FREE_TIER_AVAILABILITY"
        listOf("overload", "high demand", "capacity", "busy").any { it in message } -> "MODEL_CAPACITY"
        listOf("not enabled", "disabled").any { it in message } -> "SERVICE_DISABLED"
        listOf("not supported", "not available", "not found").any { it in message } -> "MODEL_UNAVAILABLE"
        "unavailable" in message -> "SERVICE_UNAVAILABLE"
        else -> "UNCLASSIFIED_API_ERROR"
    }
    return mapOf(
        "status" to error.statusCode.value(),
        "apiStatus" to root?.get("status")?.asString()?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) },
        "diagnosis" to diagnosis,
        "contentType" to error.responseHeaders?.contentType?.let { "${it.type}/${it.subtype}" },
        "retryAfterSeconds" to error.responseHeaders?.getFirst("Retry-After")?.toLongOrNull(),
    )
}
