package com.moeum.architecture

import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.properties.HasAnnotations
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import jakarta.annotation.PostConstruct
import jakarta.servlet.Filter
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import org.springframework.context.ApplicationListener
import org.springframework.context.Lifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.annotation.Schedules
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.HandlerInterceptor

/**
 * docs/WORKER_ROLES.md 의 역할별 등록 계약을 강제한다.
 * 역할을 지정하지 않은 빈은 모든 역할에 등록되므로 진입점과 스스로 실행되는 빈은 역할을 명시해야 한다.
 */
class ExecutionRoleRulesTest {

    private val importedClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages("com.moeum")

    private val workerRoles = setOf(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)

    @Test
    fun `HTTP 진입점은 API 역할에서만 등록된다`() {
        classes().that().areAnnotatedWith(RestController::class.java)
            .or().areAnnotatedWith(RestControllerAdvice::class.java)
            .or().implement(Filter::class.java)
            .should(registerOnlyIn(setOf(ExecutionRole.API)))
            .check(importedClasses)
    }

    @Test
    fun `스케줄 작업은 워커 역할에서만 등록된다`() {
        classes().that(haveScheduledMethods)
            .should(registerOnlyIn(workerRoles))
            .check(importedClasses)
    }

    @Test
    fun `LLM 호출 빈은 워커 역할에서만 등록된다`() {
        classes().that().implement(LlmClient::class.java)
            .or().implement(LlmProvider::class.java)
            .should(registerOnlyIn(workerRoles))
            .check(importedClasses)
    }

    // 공통 빈이라도 스스로 실행되면 등록 역할을 명시한다. 모든 역할이면 세 역할을 모두 적는다.
    @Test
    fun `스스로 실행되는 빈은 등록 역할을 명시한다`() {
        classes().that(selfActivating)
            .should(registerOnlyIn(ExecutionRole.entries.toSet()))
            .check(importedClasses)
    }

    @Test
    fun `스스로 실행되는 Bean 메서드는 등록 역할을 명시한다`() {
        methods().that().areAnnotatedWith(Bean::class.java)
            .and(DescribedPredicate.describe("return self-activating types") { method: JavaMethod ->
                selfActivatingTypes.any { method.rawReturnType.isAssignableTo(it) }
            })
            .should(object : ArchCondition<JavaMethod>("declare @ConditionalOnRole on the method or its class") {
                override fun check(method: JavaMethod, events: ConditionEvents) {
                    val roles = declaredRoles(method).ifEmpty { declaredRoles(method.owner) }
                    events.add(SimpleConditionEvent(method, roles.isNotEmpty(), "${method.fullName} roles=$roles"))
                }
            })
            .check(importedClasses)
    }

    private val haveScheduledMethods = DescribedPredicate.describe<JavaClass>("have @Scheduled methods") { javaClass ->
        javaClass.methods.any { it.isAnnotatedWith(Scheduled::class.java) }
    }

    /** 컨테이너가 호출하거나 실행을 시작하는 장치. 메시징 리스너는 이름 규칙으로 포함한다. */
    private val selfActivatingAnnotations = listOf(
        Scheduled::class.java,
        Schedules::class.java,
        Async::class.java,
        EventListener::class.java,
        TransactionalEventListener::class.java,
        PostConstruct::class.java,
    )
    private val selfActivatingTypes = listOf(
        ApplicationRunner::class.java,
        CommandLineRunner::class.java,
        Lifecycle::class.java,
        ApplicationListener::class.java,
        HandlerInterceptor::class.java,
        Filter::class.java,
    )

    private val selfActivating = DescribedPredicate.describe<JavaClass>("have self-activating methods or types") { javaClass ->
        selfActivatingTypes.any { javaClass.isAssignableTo(it) } ||
            selfActivatingAnnotations.any { javaClass.isAnnotatedWith(it) } ||
            javaClass.methods.any { method ->
                selfActivatingAnnotations.any { method.isAnnotatedWith(it) } ||
                    method.annotations.any { it.rawType.simpleName.endsWith("Listener") }
            }
    }

    private fun declaredRoles(annotated: HasAnnotations<*>): Set<ExecutionRole> =
        annotated.tryGetAnnotationOfType(ConditionalOnRole::class.java.name).orElse(null)
            ?.let { annotation ->
                (annotation.get("value").orElse(null) as? Array<*>)
                    ?.map { ExecutionRole.valueOf((it as JavaEnumConstant).name()) }
                    ?.toSet()
            }
            .orEmpty()

    private fun registerOnlyIn(allowed: Set<ExecutionRole>) =
        object : ArchCondition<JavaClass>("be annotated with @ConditionalOnRole within $allowed") {
            override fun check(javaClass: JavaClass, events: ConditionEvents) {
                val roles = declaredRoles(javaClass)
                val satisfied = roles.isNotEmpty() && allowed.containsAll(roles)
                events.add(SimpleConditionEvent(javaClass, satisfied, "${javaClass.name} roles=$roles"))
            }
        }
}
