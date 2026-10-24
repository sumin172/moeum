package com.moeum.platform.observability

import org.slf4j.MDC
import java.util.UUID

/** 작업 ID를 영속적인 실행 흐름 식별자로 사용한다. 재시작·재시도에도 같은 traceId로 검색한다. */
object WorkTrace {
    private val keys = listOf("traceId", "jobId", "recordDayId", "attempt", "generationId")

    fun <T> run(jobId: UUID, recordDayId: UUID, attempt: Int, action: () -> T): T {
        val previous = MDC.getCopyOfContextMap()
        try {
            MDC.put("traceId", jobId.toString())
            MDC.put("jobId", jobId.toString())
            MDC.put("recordDayId", recordDayId.toString())
            MDC.put("attempt", attempt.toString())
            MDC.remove("generationId")
            return action()
        } finally {
            MDC.clear()
            previous?.let(MDC::setContextMap)
        }
    }

    fun generation(id: UUID) { if (MDC.get("jobId") != null) MDC.put("generationId", id.toString()) }

    fun snapshot(): Map<String, String> = keys.mapNotNull { key -> MDC.get(key)?.let { key to it } }.toMap()
}
