package com.moeum.platform.runtime

import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

enum class ExecutionRole { API, CONVERSATION_WORKER, JOURNAL_WORKER }

/** 빈이 실행될 역할을 명시한다. 기본 역할은 API다. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Conditional(ExecutionRoleCondition::class)
annotation class ConditionalOnRole(vararg val value: ExecutionRole)

class ExecutionRoleCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val roles = (Binder.get(context.environment)
            .bind("moeum.runtime", RuntimeProperties::class.java).orElse(null) ?: RuntimeProperties()).roles
        val required = metadata.getAnnotationAttributes(ConditionalOnRole::class.java.name)
            ?.get("value") as? Array<*> ?: return false
        return required.any { ExecutionRole.valueOf(it.toString()) in roles }
    }
}
