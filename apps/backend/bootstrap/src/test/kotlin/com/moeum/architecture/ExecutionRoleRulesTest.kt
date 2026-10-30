package com.moeum.architecture

import com.moeum.platform.llm.LlmClient
import com.moeum.platform.llm.provider.LlmProvider
import com.moeum.platform.runtime.ConditionalOnRole
import com.moeum.platform.runtime.ExecutionRole
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import jakarta.servlet.Filter
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * docs/WORKER_ROLES.md 의 역할별 진입점 등록 계약을 강제한다.
 * 역할을 지정하지 않은 빈은 모든 역할에 등록되므로 진입점은 역할을 명시해야 한다.
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

    private val haveScheduledMethods = DescribedPredicate.describe<JavaClass>("have @Scheduled methods") { javaClass ->
        javaClass.methods.any { it.isAnnotatedWith(Scheduled::class.java) }
    }

    private fun registerOnlyIn(allowed: Set<ExecutionRole>) =
        object : ArchCondition<JavaClass>("be annotated with @ConditionalOnRole within $allowed") {
            override fun check(javaClass: JavaClass, events: ConditionEvents) {
                val roles = javaClass.tryGetAnnotationOfType(ConditionalOnRole::class.java.name).orElse(null)
                    ?.let { annotation ->
                        (annotation.get("value").orElse(null) as? Array<*>)
                            ?.map { ExecutionRole.valueOf((it as JavaEnumConstant).name()) }
                            ?.toSet()
                    }
                    .orEmpty()
                val satisfied = roles.isNotEmpty() && allowed.containsAll(roles)
                events.add(SimpleConditionEvent(javaClass, satisfied, "${javaClass.name} roles=$roles"))
            }
        }
}
