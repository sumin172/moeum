package com.moeum.platform.runtime

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
class WorkerConfiguration {
    @Bean(destroyMethod = "close")
    @DependsOn("entityManagerFactory")
    @ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
    fun conversationWorkerPool(properties: RuntimeProperties) =
        WorkerPool(properties.conversationConcurrency, "conversation-worker", properties.shutdownTimeout)

    @Bean(destroyMethod = "close")
    @DependsOn("entityManagerFactory")
    @ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
    fun journalWorkerPool(properties: RuntimeProperties) =
        WorkerPool(properties.journalConcurrency, "journal-worker", properties.shutdownTimeout)

    @Bean
    @ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER)
    fun conversationPollScheduler() = scheduler("conversation-poll-")

    @Bean
    @ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
    fun journalPollScheduler() = scheduler("journal-poll-")

    @Bean
    @ConditionalOnRole(ExecutionRole.JOURNAL_WORKER)
    fun journalDeliveryScheduler() = scheduler("journal-delivery-")

    private fun scheduler(name: String) = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix(name)
        setRemoveOnCancelPolicy(true)
    }
}
