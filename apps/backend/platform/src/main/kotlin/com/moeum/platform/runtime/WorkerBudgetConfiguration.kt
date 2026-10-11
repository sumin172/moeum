package com.moeum.platform.runtime

import com.moeum.platform.llm.LlmProperties
import org.springframework.context.annotation.Configuration

/** 한 프로세스의 실행 슬롯 합계가 provider 호출 예산을 넘지 않도록 한다. replica 합계는 배포 예산으로 관리한다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnRole(ExecutionRole.CONVERSATION_WORKER, ExecutionRole.JOURNAL_WORKER)
class WorkerBudgetConfiguration(runtime: RuntimeProperties, llm: LlmProperties) {
    init {
        val allocations = buildList {
            if (ExecutionRole.CONVERSATION_WORKER in runtime.roles) add("conversation-response" to runtime.conversationConcurrency)
            if (ExecutionRole.JOURNAL_WORKER in runtime.roles) add("journal-generation" to runtime.journalConcurrency)
        }
        val byProvider = allocations.groupBy { (purpose, _) ->
            requireNotNull(llm.routes[purpose]) { "moeum.llm.routes.$purpose 설정이 필요합니다" }.provider
        }
        byProvider.forEach { (provider, jobs) ->
            val limit = llm.concurrentLimit(provider)
            require(jobs.sumOf { it.second } <= limit) {
                "$provider 실행 슬롯 합계가 LLM 동시 호출 상한($limit)을 초과합니다"
            }
        }
    }
}
